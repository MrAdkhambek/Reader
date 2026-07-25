/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import com.adkhambek.reader.qr.common.ScanResult;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.adkhambek.reader.common.Result;

/**
 * Drop-in QR scanner. Stacks a CameraX {@link PreviewView} and a
 * {@link ScannerOverlayView}, owns a {@link QrScanner}, and drives the
 * "snap brackets to the detected QR's bounding box, hold, then fire the
 * callback" flow internally.
 *
 * <p>Usage:
 * <pre>{@code
 * QrScannerView qr = new QrScannerView(this);
 * qr.setLifecycleOwner(this);          // ComponentActivity / Fragment
 * qr.setListener(result -> { ... });   // fires after the hold
 * }</pre>
 *
 * <p>The host must hold {@code CAMERA} permission before the view will bind
 * the camera. The lifecycle observer is permission-aware: if the user hasn't
 * granted CAMERA yet, {@code ON_RESUME} is a no-op. The host should request
 * the permission and either {@link #start(LifecycleOwner)} once granted or
 * simply re-enter the lifecycle (next ON_RESUME will see the granted state).
 */
public final class QrScannerView extends FrameLayout {

	/** Callback for a successful scan, fired after the lock-on hold. */
	public interface Listener {
		void onScanned(@NonNull ScanResult result);

		/** Optional error sink — CameraX init failures and the like. */
		default void onError(@NonNull Throwable t) { }
	}

	private static final String TAG = "Read3rQR";

	/** Default lock-on snap duration in ms. */
	public static final long DEFAULT_SNAP_ANIM_MS = 280;
	/** Default time to keep the lock-on visible before firing the callback. */
	public static final long DEFAULT_HOLD_MS = 650;

	private final PreviewView preview;
	private final ScannerOverlayView overlay;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LifecycleHandler lifecycleHandler = new LifecycleHandler();

	// Stable field so the listener isn't re-allocated on every lifecycle
	// resume and so it stays reachable for the QrScanner's lifetime.
	private final QrScanListener internalListener = this::onScanResult;

	private QrScanner scanner;
	private LifecycleOwner lifecycleOwner;
	private Listener listener;
	private Runnable pendingDeliver;

	private long snapAnimMs = DEFAULT_SNAP_ANIM_MS;
	private long holdMs = DEFAULT_HOLD_MS;
	private boolean autoStopOnScan = true;

	public QrScannerView(@NonNull Context ctx) {
		this(ctx, null);
	}

	public QrScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		this(ctx, attrs, 0);
	}

	public QrScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(ctx, attrs, defStyleAttr);

		preview = new PreviewView(ctx);
		preview.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(preview);

		overlay = new ScannerOverlayView(ctx);
		overlay.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(overlay);
	}

	// --- Public API --------------------------------------------------------

	/**
	 * Observe the host's lifecycle so the camera auto-starts on {@code ON_RESUME}
	 * (subject to {@link android.Manifest.permission#CAMERA} being granted),
	 * auto-stops on {@code ON_PAUSE}, and is fully shut down on {@code ON_DESTROY}.
	 */
	public void setLifecycleOwner(@NonNull LifecycleOwner owner) {
		if (lifecycleOwner != null) {
			lifecycleOwner.getLifecycle().removeObserver(lifecycleHandler);
		}
		lifecycleOwner = owner;
		owner.getLifecycle().addObserver(lifecycleHandler);
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	/** Override the snap-to-QR animation duration (default {@value DEFAULT_SNAP_ANIM_MS} ms). */
	public void setSnapAnimDuration(long ms) {
		this.snapAnimMs = Math.max(1, ms);
	}

	/** Override the time the lock-on is held before {@link Listener#onScanned} fires
	 *  (default {@value DEFAULT_HOLD_MS} ms). Use 0 to fire immediately. */
	public void setHoldDuration(long ms) {
		this.holdMs = Math.max(0, ms);
	}

	/** When {@code true} (default) the camera stops after a successful scan.
	 *  Set {@code false} if you want continuous multi-scan via {@link #resumeScanning}. */
	public void setAutoStopOnScan(boolean autoStop) {
		this.autoStopOnScan = autoStop;
	}

	/** Re-arm the analyzer to accept the next decode. The reticle stays where
	 *  the previous lock-on left it; call {@link ScannerOverlayView#animateReticleTo}
	 *  via {@link #getScannerOverlay()} if you want it back to center. */
	public void resumeScanning() {
		cancelPendingDeliver();
		overlay.unfreezeScanLine();
		if (scanner != null) {
			scanner.resume();
			if (lifecycleOwner != null && hasCameraPermission()) {
				scanner.start(preview, lifecycleOwner, internalListener);
			}
		}
	}

	/** Stop the camera and analysis without tearing down. Pending lock-on
	 *  deliveries are cancelled so {@link Listener#onScanned} can't fire after this. */
	public void stop() {
		cancelPendingDeliver();
		if (scanner != null) scanner.stop();
	}

	/**
	 * Manually start the camera. Use this when the host doesn't have a
	 * {@link LifecycleOwner} (or has one but the permission was just granted —
	 * the next lifecycle resume will also work, but this is the prompt path).
	 *
	 * <p>No-op if CAMERA permission is missing; surfaces ERROR to the listener.
	 */
	public void start(@NonNull LifecycleOwner owner) {
		if (lifecycleOwner == null) lifecycleOwner = owner;
		if (!hasCameraPermission()) {
			if (listener != null) {
				listener.onError(new SecurityException("CAMERA permission not granted"));
			}
			return;
		}
		ensureScanner();
		scanner.start(preview, owner, internalListener);
	}

	/** Release all resources. The view is unusable after this — typically wired
	 *  to {@code ON_DESTROY} automatically by {@link #setLifecycleOwner}.
	 *  Safe to call directly: the lifecycle observer is unregistered too, so a
	 *  later ON_RESUME from the same lifecycle owner won't re-start the scanner. */
	public void shutdown() {
		cancelPendingDeliver();
		if (scanner != null) {
			scanner.shutdown();
			scanner = null;
		}
		if (lifecycleOwner != null) {
			lifecycleOwner.getLifecycle().removeObserver(lifecycleHandler);
			lifecycleOwner = null;
		}
	}

	// --- Accessors for advanced customization -----------------------------

	@NonNull public PreviewView getPreviewView() { return preview; }
	@NonNull public ScannerOverlayView getScannerOverlay() { return overlay; }

	// --- Internals --------------------------------------------------------

	private boolean hasCameraPermission() {
		return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_GRANTED;
	}

	private void ensureScanner() {
		if (scanner == null) scanner = new QrScanner(getContext());
	}

	private void cancelPendingDeliver() {
		if (pendingDeliver != null) {
			main.removeCallbacks(pendingDeliver);
			pendingDeliver = null;
		}
	}

	private void onScanResult(Result<ScanResult, Throwable> result) {
		if (result.isErr()) {
			final Throwable t = result.error();
			Log.w(TAG, "scan error", t);
			if (listener != null) listener.onError(t);
			return;
		}
		deliverScanned(result.value());
	}

	private void deliverScanned(@NonNull ScanResult result) {
		final RectF target = overlay.imageRectToView(
				result.imageWidth(), result.imageHeight(), result.rotationDegrees(),
				result.xs(), result.ys());
		overlay.freezeScanLine();
		overlay.animateReticleTo(target, snapAnimMs);

		cancelPendingDeliver();
		pendingDeliver = () -> {
			pendingDeliver = null;
			if (autoStopOnScan && scanner != null) scanner.stop();
			if (listener != null) listener.onScanned(result);
		};
		main.postDelayed(pendingDeliver, holdMs);
	}

	/** Outer class can't implement DefaultLifecycleObserver directly without
	 *  exposing the lifecycle methods on the public API surface; keep them inside. */
	private final class LifecycleHandler implements DefaultLifecycleObserver {
		@Override
		public void onResume(@NonNull LifecycleOwner owner) {
			// Permission gate: if CAMERA isn't granted yet, sit idle. The host is
			// expected to request the permission and call start() (or just let
			// the next ON_RESUME pick it up).
			if (!hasCameraPermission()) return;
			ensureScanner();
			scanner.resume();
			scanner.start(preview, owner, internalListener);
		}

		@Override
		public void onPause(@NonNull LifecycleOwner owner) {
			stop();
		}

		@Override
		public void onDestroy(@NonNull LifecycleOwner owner) {
			// shutdown() now removes the observer and nulls lifecycleOwner itself.
			shutdown();
		}
	}
}
