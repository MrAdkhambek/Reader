/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers the preview-mapping maths behind {@link ScannerOverlayView#imageRectToView}:
 * the four display rotations, FILL_CENTER letterboxing, padding, and every
 * degenerate input that used to be able to produce a {@code Float.MAX_VALUE} box.
 */
public class PreviewGeometryTest {

	private static final float EPS = 0.001f;

	/** Two opposite corners of a QR, in pre-rotation image space. */
	private static final float[] XS = {10f, 30f};
	private static final float[] YS = {20f, 40f};

	// --- Rotations. Image is 100x200; the view matches the *displayed* aspect
	//     so scale is exactly 1 and offsets are 0, isolating the rotation maths.

	@Test public void rotation0_isIdentity() {
		final float[] b = PreviewGeometry.boundsForPoints(100, 200, 0, 100f, 200f, 0f, XS, YS);
		assertArrayEquals(new float[]{10f, 20f, 30f, 40f}, b, EPS);
	}

	/** 90°: x' = imageHeight - y, y' = x. */
	@Test public void rotation90_swapsAxesAndMirrorsY() {
		final float[] b = PreviewGeometry.boundsForPoints(100, 200, 90, 200f, 100f, 0f, XS, YS);
		assertArrayEquals(new float[]{160f, 10f, 180f, 30f}, b, EPS);
	}

	/** 180°: both axes mirrored. */
	@Test public void rotation180_mirrorsBothAxes() {
		final float[] b = PreviewGeometry.boundsForPoints(100, 200, 180, 100f, 200f, 0f, XS, YS);
		assertArrayEquals(new float[]{70f, 160f, 90f, 180f}, b, EPS);
	}

	/** 270°: x' = y, y' = imageWidth - x. */
	@Test public void rotation270_swapsAxesAndMirrorsX() {
		final float[] b = PreviewGeometry.boundsForPoints(100, 200, 270, 200f, 100f, 0f, XS, YS);
		assertArrayEquals(new float[]{20f, 70f, 40f, 90f}, b, EPS);
	}

	// --- FILL_CENTER letterboxing ---------------------------------------------

	/**
	 * A tall image in a square view: FILL_CENTER scales to cover width, so the
	 * image overflows vertically and is centered. The image's center point must
	 * land on the view's center, and the overflow must be negative-offset — the
	 * box is allowed outside the view.
	 */
	@Test public void fillCenter_centersVerticalOverflow() {
		final float[] b = PreviewGeometry.boundsForPoints(
				100, 200, 0, 100f, 100f, 0f, new float[]{50f}, new float[]{100f});
		assertEquals(50f, b[0], EPS);   // image center x → view center x
		assertEquals(50f, b[1], EPS);   // image center y → view center y
	}

	@Test public void fillCenter_edgesOverflowSymmetrically() {
		final float[] b = PreviewGeometry.boundsForPoints(
				100, 200, 0, 100f, 100f, 0f, new float[]{0f, 100f}, new float[]{0f, 200f});
		// scale = max(100/100, 100/200) = 1 → 200px of image height in a 100px view,
		// centered: top at -50, bottom at 150.
		assertArrayEquals(new float[]{0f, -50f, 100f, 150f}, b, EPS);
	}

	/** A wide image in a square view scales up to cover the height. */
	@Test public void fillCenter_scalesUpToCover() {
		final float[] b = PreviewGeometry.boundsForPoints(
				200, 100, 0, 200f, 200f, 0f, new float[]{0f, 200f}, new float[]{0f, 100f});
		// scale = max(200/200, 200/100) = 2 → 400px wide in a 200px view: x from -100 to 300.
		assertArrayEquals(new float[]{-100f, 0f, 300f, 200f}, b, EPS);
	}

	// --- Padding ---------------------------------------------------------------

	@Test public void padding_expandsBoxOnAllSides() {
		final float[] b = PreviewGeometry.boundsForPoints(100, 200, 0, 100f, 200f, 5f, XS, YS);
		assertArrayEquals(new float[]{5f, 15f, 35f, 45f}, b, EPS);
	}

	// --- Degenerate input ------------------------------------------------------

	@Test public void nullPoints_returnNull() {
		assertNull(PreviewGeometry.boundsForPoints(100, 200, 0, 100f, 200f, 0f, null, YS));
		assertNull(PreviewGeometry.boundsForPoints(100, 200, 0, 100f, 200f, 0f, XS, null));
	}

	@Test public void emptyPoints_returnNull() {
		assertNull(PreviewGeometry.boundsForPoints(
				100, 200, 0, 100f, 200f, 0f, new float[0], new float[0]));
	}

	/** Mismatched arrays would read past the end of ys. */
	@Test public void mismatchedPointArrays_returnNull() {
		assertNull(PreviewGeometry.boundsForPoints(
				100, 200, 0, 100f, 200f, 0f, new float[]{1f, 2f}, new float[]{1f}));
	}

	/** The view hasn't been laid out yet — getWidth()/getHeight() are still 0. */
	@Test public void zeroSizedView_returnsNull() {
		assertNull(PreviewGeometry.boundsForPoints(100, 200, 0, 0f, 0f, 0f, XS, YS));
		assertNull(PreviewGeometry.boundsForPoints(100, 200, 0, 100f, 0f, 0f, XS, YS));
	}

	@Test public void zeroSizedImage_returnsNull() {
		assertNull(PreviewGeometry.boundsForPoints(0, 0, 0, 100f, 200f, 0f, XS, YS));
	}

	/** A single finder pattern is a zero-area box, not an error — padding still applies. */
	@Test public void singlePoint_yieldsPaddedPointBox() {
		final float[] b = PreviewGeometry.boundsForPoints(
				100, 200, 0, 100f, 200f, 4f, new float[]{50f}, new float[]{60f});
		assertArrayEquals(new float[]{46f, 56f, 54f, 64f}, b, EPS);
	}
}
