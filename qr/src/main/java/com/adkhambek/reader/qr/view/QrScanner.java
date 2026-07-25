/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import com.adkhambek.reader.qr.common.ScanResult;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.adkhambek.reader.common.Result;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin wrapper around CameraX + {@link QrAnalyzer}. Call {@link #start} once
 * the host activity has the {@code CAMERA} permission and is in
 * {@code onResume}. Call {@link #stop} from {@code onPause} (or rely on the
 * lifecycle owner to do it automatically).
 *
 * <p>Listener events arrive on the main looper. After {@link QrScanListener#onResult}
 * fires with an {@code Ok}, the analyzer ignores further frames until {@link #resume}.
 *
 * <p>The listener is held by a strong reference. Pass an {@link Object}-backed
 * listener (lambda / method ref / anonymous class) and it stays reachable until
 * {@link #shutdown} clears it — earlier versions used a {@code WeakReference}
 * here, which silently dropped events after GC.
 */
public final class QrScanner {
	private static final String TAG = "Read3rQR";

	private final Context context;
	private final ExecutorService analysisExec = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "Read3r-qr-analyzer");
		t.setDaemon(true);
		return t;
	});
	private final Handler main = new Handler(Looper.getMainLooper());

	private QrAnalyzer analyzer;
	private ProcessCameraProvider provider;
	private volatile QrScanListener listener;
	private volatile boolean cancelled;
	// Bumped on every start() and stop(); the CameraX-init listener captures
	// its own gen at registration time and bails if a later start/stop has
	// superseded it. Without this, a slow-resolving provider future from a
	// pre-onPause start() can call bind() after stop() unbound the camera.
	private volatile int startGen;
	// Routed through fields rather than captured by the CameraX-init lambda so
	// shutdown() can null them and the pending listener can't pin a destroyed
	// Activity until the process-wide camera-provider future resolves.
	private PreviewView previewView;
	private LifecycleOwner lifecycle;

	public QrScanner(Context context) {
		this.context = context.getApplicationContext();
	}

	public void start(PreviewView previewView, LifecycleOwner lifecycle, QrScanListener listener) {
		this.listener = listener;
		this.previewView = previewView;
		this.lifecycle = lifecycle;
		final int myGen = ++startGen;
		main.post(this::deliverScanning);

		final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
		future.addListener(() -> {
			if (cancelled || myGen != startGen) return;
			final PreviewView pv = this.previewView;
			final LifecycleOwner lo = this.lifecycle;
			if (pv == null || lo == null) return;
			try {
				provider = future.get();
				if (myGen != startGen) return;
				bind(pv, lo);
			} catch (Throwable t) {
				Log.e(TAG, "CameraProvider init failed", t);
				main.post(() -> deliverResult(Result.err(t)));
			}
		}, ContextCompat.getMainExecutor(context));
	}

	private void bind(PreviewView previewView, LifecycleOwner lifecycle) {
		final Preview preview = new Preview.Builder().build();
		preview.setSurfaceProvider(previewView.getSurfaceProvider());

		analyzer = new QrAnalyzer(result -> main.post(() -> deliverResult(Result.ok(result))));

		final ImageAnalysis analysis = new ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build();
		analysis.setAnalyzer(analysisExec, analyzer);

		provider.unbindAll();
		provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
	}

	public void stop() {
		++startGen;
		if (provider != null) provider.unbindAll();
	}

	/** Drop the listener reference. Useful after {@link #stop()} when the caller
	 *  doesn't intend to deliver any further events through this scanner (e.g.
	 *  a Flow's awaitClose). The scanner can be re-armed with a fresh listener
	 *  via another {@link #start} call. */
	public void clearListener() {
		listener = null;
	}

	/** Re-arm the analyzer after a successful scan so the next frame can decode again. */
	public void resume() {
		if (analyzer != null) analyzer.reset();
	}

	public void shutdown() {
		cancelled = true;
		previewView = null;
		lifecycle = null;
		stop();
		analysisExec.shutdown();
		listener = null;
	}

	private void deliverScanning() {
		final QrScanListener l = this.listener;
		if (l != null) l.onScanning();
	}

	private void deliverResult(Result<ScanResult, Throwable> result) {
		final QrScanListener l = this.listener;
		if (l != null) l.onResult(result);
	}
}
