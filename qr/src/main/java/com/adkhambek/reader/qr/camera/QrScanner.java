/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RestrictTo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import com.adkhambek.reader.qr.QrCode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX binding behind {@link com.adkhambek.reader.qr.QrScannerView}: a preview use case plus an
 * analysis use case running {@link QrAnalyzer}. Everything except analysis
 * runs on the main thread, and results are posted there.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class QrScanner {

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public interface Sink {
		void onCode(QrCode code);

		void onError(Throwable t);
	}

	private static final String TAG = "Read3rQR";

	private final Context context;
	private final ExecutorService analysisExec = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "read3r-qr-analyzer");
		t.setDaemon(true);
		return t;
	});
	private final Handler main = new Handler(Looper.getMainLooper());

	private QrAnalyzer analyzer;
	private ProcessCameraProvider provider;
	private Sink sink;
	private boolean shutDown;
	// Bumped by start() and stop(). The provider listener captures its own
	// value and bails if a later call superseded it, so a slow provider future
	// from before onPause cannot bind the camera after stop().
	private int startGen;
	// Fields, not lambda captures, so shutdown() can drop them and a pending
	// provider future cannot pin a destroyed Activity.
	private PreviewView previewView;
	private LifecycleOwner lifecycle;

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public QrScanner(Context context) {
		this.context = context.getApplicationContext();
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public void start(PreviewView previewView, LifecycleOwner lifecycle, Sink sink) {
		this.previewView = previewView;
		this.lifecycle = lifecycle;
		this.sink = sink;
		final int myGen = ++startGen;

		final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
		future.addListener(() -> {
			if (shutDown || myGen != startGen) return;
			final PreviewView pv = this.previewView;
			final LifecycleOwner lo = this.lifecycle;
			if (pv == null || lo == null) return;
			try {
				provider = future.get();
				bind(pv, lo);
			} catch (Throwable t) {
				Log.e(TAG, "camera init failed", t);
				final Sink s = this.sink;
				if (s != null) s.onError(t);
			}
		}, ContextCompat.getMainExecutor(context));
	}

	private void bind(PreviewView previewView, LifecycleOwner lifecycle) {
		final Preview preview = new Preview.Builder().build();
		preview.setSurfaceProvider(previewView.getSurfaceProvider());

		final int gen = startGen;
		analyzer = new QrAnalyzer(code -> main.post(() -> {
			if (isStale(shutDown, gen, startGen)) return;
			final Sink s = sink;
			if (s != null) s.onCode(code);
		}));
		final ImageAnalysis analysis = new ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build();
		analysis.setAnalyzer(analysisExec, analyzer);

		provider.unbindAll();
		provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
	}

	/** True when a result decoded under {@code gen} must be dropped: stop()/start()/shutdown() ran since. */
	static boolean isStale(boolean shutDown, int gen, int currentGen) {
		return shutDown || gen != currentGen;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public void stop() {
		++startGen;
		if (provider != null) provider.unbindAll();
	}

	/** Re-arm the analyzer so the next frame can decode again. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public void resume() {
		if (analyzer != null) analyzer.reset();
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public void shutdown() {
		shutDown = true;
		previewView = null;
		lifecycle = null;
		sink = null;
		stop();
		analysisExec.shutdown();
	}
}
