/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.common;

/**
 * One successful decode. Carries enough information to draw the detected QR's
 * bounding box on top of the camera preview: the original (pre-rotation) image
 * dimensions, the rotation CameraX would apply for display, and the finder
 * pattern coordinates ZXing returned (image space, pre-rotation).
 *
 * <p>QR codes always have at least three finder patterns (top-left, top-right,
 * bottom-left), so {@link #xs}/{@link #ys} are guaranteed to be non-empty.
 */
public record ScanResult(
		String text,
		float[] xs,
		float[] ys,
		int imageWidth,
		int imageHeight,
		int rotationDegrees
) {
}
