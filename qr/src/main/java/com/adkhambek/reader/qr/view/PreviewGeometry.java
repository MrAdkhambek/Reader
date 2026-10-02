/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

/**
 * The camera-preview coordinate maths, kept free of any Android type.
 *
 * <p>Split out of {@link ScannerOverlayView} so it can be unit tested: the
 * rotation and letterboxing arithmetic is where the bugs live, but exercising it
 * through a {@code View} would need a laid-out instance and a real
 * {@code RectF}, neither of which exists on a plain JVM test classpath.
 */
final class PreviewGeometry {

	private PreviewGeometry() {
	}

	/**
	 * Map points in pre-rotation image space to a padded bounding box in view space.
	 *
	 * <p>Assumes the preview underneath uses {@code PreviewView.ScaleType.FILL_CENTER}
	 * (CameraX's default): the image is scaled to cover the view, and the overflow
	 * is centered — so the box can legitimately extend outside the view bounds.
	 *
	 * @param rotationDegrees 0 / 90 / 180 / 270 — how CameraX will rotate for display
	 * @return {@code {left, top, right, bottom}}, or null if the inputs are
	 *         degenerate (no points, mismatched arrays, or a zero-sized image or view)
	 */
	static float[] boundsForPoints(int imageWidth, int imageHeight, int rotationDegrees,
	                               float viewWidth, float viewHeight, float pad,
	                               float[] xs, float[] ys) {
		if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) return null;

		final boolean swap = rotationDegrees == 90 || rotationDegrees == 270;
		final int effImgW = swap ? imageHeight : imageWidth;
		final int effImgH = swap ? imageWidth : imageHeight;
		if (viewWidth <= 0 || viewHeight <= 0 || effImgW <= 0 || effImgH <= 0) return null;

		// FILL_CENTER: scale so the image covers the view, then center the overflow.
		final float scale = Math.max(viewWidth / effImgW, viewHeight / effImgH);
		final float offsetX = (viewWidth - effImgW * scale) / 2f;
		final float offsetY = (viewHeight - effImgH * scale) / 2f;

		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < xs.length; ++i) {
			final float xi = xs[i];
			final float yi = ys[i];
			final float rx;
			final float ry;
			switch (rotationDegrees) {
				case 90:  rx = imageHeight - yi; ry = xi; break;
				case 180: rx = imageWidth  - xi; ry = imageHeight - yi; break;
				case 270: rx = yi;               ry = imageWidth  - xi; break;
				default:  rx = xi;               ry = yi;
			}
			final float vx = rx * scale + offsetX;
			final float vy = ry * scale + offsetY;
			if (vx < minX) minX = vx;
			if (vy < minY) minY = vy;
			if (vx > maxX) maxX = vx;
			if (vy > maxY) maxY = vy;
		}

		return new float[]{minX - pad, minY - pad, maxX + pad, maxY + pad};
	}
}
