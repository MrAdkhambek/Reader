/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

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

/**
 * Drop-in QR scanner: a CameraX preview under a {@link ScannerOverlayView}.
 * On a decode it snaps the reticle onto the code, holds, then calls back.
 *
 * <pre>{@code
 * QrScannerView qr = new QrScannerView(this);
 * qr.start(this, code -> show(code.text()));   // ComponentActivity / Fragment
 * }</pre>
 *
 * <p>The host requests {@code CAMERA}. Without it the view sits idle; call
 * {@link #start} again once it is granted.
 */
public final class QrScannerView extends FrameLayout {

	/** Results arrive on the main thread. */
	public interface Listener {
		/** A code was decoded; fires after the lock-on hold. */
		void onScanned(@NonNull QrCode code);

		/** Camera initialisation failed. */
		default void onError(@NonNull Throwable t) {
		}
	}

	private static final String TAG = "Read3rQR";

	/** Default lock-on snap duration in ms. */
	public static final long DEFAULT_SNAP_ANIM_MS = 280;
	/** Default time the lock-on stays visible before the callback, in ms. */
	public static final long DEFAULT_HOLD_MS = 650;

	private final PreviewView preview;
	private final ScannerOverlayView overlay;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LifecycleHandler lifecycleHandler = new LifecycleHandler();
	private final QrScanner.Sink sink = new QrScanner.Sink() {
		@Override
		public void onCode(QrCode code) {
			deliverScanned(code);
		}

		@Override
		public void onError(Throwable t) {
			Log.w(TAG, "camera error", t);
			if (listener != null) listener.onError(t);
		}
	};

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

	/**
	 * Scan while {@code owner} is resumed and CAMERA is granted: the camera
	 * stops on pause and is released on destroy. Call again after the user
	 * grants CAMERA — an already-resumed owner starts the camera immediately.
	 */
	public void start(@NonNull LifecycleOwner owner, @NonNull Listener listener) {
		this.listener = listener;
		if (lifecycleOwner != null) lifecycleOwner.getLifecycle().removeObserver(lifecycleHandler);
		lifecycleOwner = owner;
		// addObserver replays the events up to the current state, so ON_RESUME
		// arrives now if the owner is already resumed.
		owner.getLifecycle().addObserver(lifecycleHandler);
	}

	/** Lock-on snap animation duration (default {@value DEFAULT_SNAP_ANIM_MS} ms). */
	public void setSnapAnimDuration(long ms) {
		this.snapAnimMs = Math.max(1, ms);
	}

	/** Time the lock-on is held before {@link Listener#onScanned} (default {@value DEFAULT_HOLD_MS} ms; 0 fires at once). */
	public void setHoldDuration(long ms) {
		this.holdMs = Math.max(0, ms);
	}

	/** When true (default) the camera stops after a scan; false allows continuous scanning via {@link #resumeScanning}. */
	public void setAutoStopOnScan(boolean autoStop) {
		this.autoStopOnScan = autoStop;
	}

	/** Accept the next code. The reticle stays where the last lock-on left it. */
	public void resumeScanning() {
		cancelPendingDeliver();
		overlay.unfreezeScanLine();
		if (scanner != null) {
			scanner.resume();
			if (lifecycleOwner != null && hasCameraPermission()) {
				scanner.start(preview, lifecycleOwner, sink);
			}
		}
	}

	/** Stop the camera without releasing it. A pending lock-on is cancelled. */
	public void stop() {
		cancelPendingDeliver();
		if (scanner != null) scanner.stop();
	}

	/** Release everything. Called automatically on the owner's ON_DESTROY. */
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

	@NonNull public PreviewView getPreviewView() { return preview; }

	@NonNull public ScannerOverlayView getScannerOverlay() { return overlay; }

	private boolean hasCameraPermission() {
		return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_GRANTED;
	}

	private void cancelPendingDeliver() {
		if (pendingDeliver != null) {
			main.removeCallbacks(pendingDeliver);
			pendingDeliver = null;
		}
	}

	private void deliverScanned(@NonNull QrCode code) {
		final RectF target = overlay.imageRectToView(
				code.imageWidth(), code.imageHeight(), code.rotationDegrees(), code.xs(), code.ys());
		overlay.freezeScanLine();
		overlay.animateReticleTo(target, snapAnimMs);

		cancelPendingDeliver();
		pendingDeliver = () -> {
			pendingDeliver = null;
			if (autoStopOnScan && scanner != null) scanner.stop();
			if (listener != null) listener.onScanned(code);
		};
		main.postDelayed(pendingDeliver, holdMs);
	}

	/** Kept inner so the lifecycle callbacks are not public API on the view. */
	private final class LifecycleHandler implements DefaultLifecycleObserver {
		@Override
		public void onResume(@NonNull LifecycleOwner owner) {
			if (!hasCameraPermission()) return;
			if (scanner == null) scanner = new QrScanner(getContext());
			scanner.resume();
			scanner.start(preview, owner, sink);
		}

		@Override
		public void onPause(@NonNull LifecycleOwner owner) {
			stop();
		}

		@Override
		public void onDestroy(@NonNull LifecycleOwner owner) {
			shutdown();
		}
	}
}
