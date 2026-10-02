/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes QR codes from CameraX YUV frames and reports the first one. Further
 * frames are ignored until {@link #reset()}.
 *
 * <p>Tuned for a live stream: no {@code TRY_HARDER} (a missed frame is retried
 * a few milliseconds later anyway), {@link QRCodeReader} directly rather than
 * the multi-format dispatcher, and one luminance buffer reused across frames.
 */
final class QrAnalyzer implements ImageAnalysis.Analyzer {

	interface Callback {
		void onDecoded(QrCode code);
	}

	private static final String TAG = "Read3rQR";

	private final Callback callback;
	private final QRCodeReader reader = new QRCodeReader();
	private final AtomicBoolean done = new AtomicBoolean(false);
	// Touched only on the analysis thread.
	private byte[] luma = new byte[0];

	QrAnalyzer(Callback callback) {
		this.callback = callback;
	}

	void reset() {
		done.set(false);
	}

	// ImageProxy.getImage() is @ExperimentalGetImage; we only read the YUV planes while the proxy is open.
	@SuppressLint("UnsafeOptInUsageError")
	@Override
	public void analyze(@NonNull ImageProxy image) {
		if (done.get()) {
			image.close();
			return;
		}
		try (image) {
			final Image media = image.getImage();
			if (media == null || media.getFormat() != ImageFormat.YUV_420_888) return;

			final Image.Plane y = media.getPlanes()[0];
			final int width = image.getWidth();
			final int height = image.getHeight();
			if (luma.length != width * height) luma = new byte[width * height];
			packLuminance(y.getBuffer(), y.getRowStride(), width, height, luma);

			final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
					new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)));
			try {
				final Result r = reader.decode(bitmap);
				if (done.compareAndSet(false, true)) {
					callback.onDecoded(toQrCode(r, width, height,
							image.getImageInfo().getRotationDegrees()));
				}
			} catch (ReaderException noCodeInThisFrame) {
				// NotFound, Checksum or Format: nothing usable here; the next frame is coming.
			} finally {
				reader.reset();
			}
		} catch (Throwable t) {
			Log.w(TAG, "analyze failed", t);
		}
	}

	private static QrCode toQrCode(Result r, int w, int h, int rotation) {
		final ResultPoint[] points = r.getResultPoints();
		final int n = (points == null) ? 0 : points.length;
		final float[] xs = new float[n];
		final float[] ys = new float[n];
		for (int i = 0; i < n; ++i) {
			if (points[i] == null) continue;
			xs[i] = points[i].getX();
			ys[i] = points[i].getY();
		}
		return new QrCode(r.getText(), xs, ys, w, h, rotation);
	}

	/** CameraX rows are padded to rowStride; ZXing needs them packed into {@code out}. */
	private static void packLuminance(ByteBuffer src, int rowStride, int width, int height, byte[] out) {
		if (rowStride == width) {
			src.get(out, 0, Math.min(out.length, src.remaining()));
			return;
		}
		for (int row = 0; row < height; ++row) {
			src.position(row * rowStride);
			src.get(out, row * width, Math.min(width, src.remaining()));
		}
	}
}
