/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import com.adkhambek.reader.qr.common.ScanResult;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX {@link ImageAnalysis.Analyzer} that decodes QR codes from each YUV
 * frame via ZXing and emits a {@link ScanResult} on the first successful decode.
 * Subsequent frames are ignored until {@link #reset()}.
 */
public final class QrAnalyzer implements ImageAnalysis.Analyzer {

	public interface Callback {
		void onDecoded(ScanResult result);
	}

	private static final String TAG = "Read3rQR";

	private final Callback callback;
	private final MultiFormatReader reader;
	private final AtomicBoolean done = new AtomicBoolean(false);

	public QrAnalyzer(Callback callback) {
		this.callback = callback;
		this.reader = new MultiFormatReader();
		final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
		hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
		hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
		reader.setHints(hints);
	}

	public void reset() {
		done.set(false);
	}

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
            final byte[] buf = packLuminance(y.getBuffer(), y.getRowStride(), width, height);

            final PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    buf, width, height, 0, 0, width, height, false);
            final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));

            try {
                final Result r = reader.decodeWithState(bitmap);
                if (done.compareAndSet(false, true)) {
                    callback.onDecoded(toScanResult(r, width, height,
                            image.getImageInfo().getRotationDegrees()));
                }
            } catch (NotFoundException nf) {
                // No QR in this frame; expected.
            } finally {
                reader.reset();
            }
        } catch (Throwable t) {
            Log.w(TAG, "analyze failed", t);
        }
	}

	private static ScanResult toScanResult(Result r, int w, int h, int rot) {
		final ResultPoint[] points = r.getResultPoints();
		final int n = (points == null) ? 0 : points.length;
		final float[] xs = new float[n];
		final float[] ys = new float[n];
		for (int i = 0; i < n; ++i) {
			if (points[i] == null) continue;
			xs[i] = points[i].getX();
			ys[i] = points[i].getY();
		}
		return new ScanResult(r.getText(), xs, ys, w, h, rot);
	}

	private static byte[] packLuminance(ByteBuffer src, int rowStride, int width, int height) {
		if (rowStride == width) {
			final byte[] out = new byte[width * height];
			src.get(out, 0, Math.min(out.length, src.remaining()));
			return out;
		}
		final byte[] out = new byte[width * height];
		final byte[] row = new byte[rowStride];
		int o = 0;
		for (int r = 0; r < height; ++r) {
			src.position(r * rowStride);
			src.get(row, 0, Math.min(rowStride, src.remaining()));
			System.arraycopy(row, 0, out, o, width);
			o += width;
		}
		return out;
	}
}
