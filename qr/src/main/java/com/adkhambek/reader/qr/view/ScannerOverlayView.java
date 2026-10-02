/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Telegram-style viewfinder. Static state: dim everything outside a centered
 * reticle, draw L-shaped corner brackets, sweep a horizontal scan line.
 *
 * <p>Detection state: call {@link #animateReticleTo} with the QR's bounding
 * box in <em>this view's</em> coordinates and the brackets slide+resize there
 * over the given duration. {@link #imageRectToView} converts a list of points
 * in pre-rotation image space to a view-space bounding rect, handling the
 * {@link androidx.camera.view.PreviewView} default FILL_CENTER scale type.
 *
 * <p>Pure-Canvas, no offscreen buffers or Porter-Duff compositing.
 */
public final class ScannerOverlayView extends View {

	private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path dimPath = new Path();

	private final RectF defaultReticle = new RectF();
	private final RectF reticle = new RectF();
	private final RectF animFrom = new RectF();
	private final RectF animTo = new RectF();
	private long animStartMs;
	private long animDurationMs;
	private boolean animating;

	private float reticleRatio = 0.72f;
	private final float cornerLen;
	private final float cornerStroke;
	private final float reticleRadius;
	private final float density;

	private final long startMs = SystemClock.uptimeMillis();
	private boolean drawScanLine = true;

	public ScannerOverlayView(Context ctx) {
		this(ctx, null);
	}

	public ScannerOverlayView(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);

		dimPaint.setColor(0xCC000000);
		cornerPaint.setColor(Color.WHITE);
		cornerPaint.setStyle(Paint.Style.STROKE);
		cornerPaint.setStrokeCap(Paint.Cap.ROUND);
		scanLinePaint.setColor(0xFF34C759);
		scanLinePaint.setStrokeCap(Paint.Cap.ROUND);

		density = getResources().getDisplayMetrics().density;
		cornerLen = 28f * density;
		cornerStroke = 4f * density;
		reticleRadius = 16f * density;
		cornerPaint.setStrokeWidth(cornerStroke);
		scanLinePaint.setStrokeWidth(2f * density);
	}

	public void setReticleRatio(float ratio) {
		reticleRatio = Math.max(0.2f, Math.min(0.95f, ratio));
		layoutDefaultReticle(getWidth(), getHeight());
		if (!animating) reticle.set(defaultReticle);
		invalidate();
	}

	/** Stop the scan-line sweep — useful once detection has succeeded. */
	public void freezeScanLine() {
		drawScanLine = false;
		invalidate();
	}

	/** Resume the scan-line sweep. Pair with {@link #freezeScanLine()} when
	 *  continuing to scan after a lock-on (e.g. multi-scan flows). */
	public void unfreezeScanLine() {
		drawScanLine = true;
		invalidate();
	}

	public RectF getReticleBounds() {
		return new RectF(reticle);
	}

	public void animateReticleTo(RectF target, long durationMs) {
		animFrom.set(reticle);
		animTo.set(target);
		animStartMs = SystemClock.uptimeMillis();
		animDurationMs = Math.max(1, durationMs);
		animating = true;
		invalidate();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		layoutDefaultReticle(w, h);
		reticle.set(defaultReticle);
	}

	private void layoutDefaultReticle(int w, int h) {
		final float side = Math.min(w, h) * reticleRatio;
		final float left = (w - side) / 2f;
		final float top = (h - side) / 2f;
		defaultReticle.set(left, top, left + side, top + side);
	}

	@Override
	protected void onDraw(Canvas c) {
		super.onDraw(c);
		if (reticle.isEmpty() && defaultReticle.isEmpty()) return;

		if (animating) tickAnimation();

		final float w = getWidth();
		final float h = getHeight();
		final RectF r = reticle;

		// Dim everything outside a rounded-rect hole. EVEN_ODD fill: the outer
		// rect minus the inner rounded rect = the dim region. Corner radius is
		// the same value the brackets curve at, so the dim and the brackets meet
		// flush instead of the dim showing through at each corner.
		final float radius = currentCornerRadius();
		dimPath.rewind();
		dimPath.setFillType(Path.FillType.EVEN_ODD);
		dimPath.addRect(0, 0, w, h, Path.Direction.CW);
		dimPath.addRoundRect(r, radius, radius, Path.Direction.CW);
		c.drawPath(dimPath, dimPaint);

		drawCornerBrackets(c);
		if (drawScanLine && !animating) drawScanLineSweep(c);

		if (animating || drawScanLine) postInvalidateOnAnimation();
	}

	private void tickAnimation() {
		final long now = SystemClock.uptimeMillis();
		final float raw = (now - animStartMs) / (float) animDurationMs;
		final float t = raw >= 1f ? 1f : easeOutCubic(raw);
		reticle.left = lerp(animFrom.left, animTo.left, t);
		reticle.top = lerp(animFrom.top, animTo.top, t);
		reticle.right = lerp(animFrom.right, animTo.right, t);
		reticle.bottom = lerp(animFrom.bottom, animTo.bottom, t);
		if (raw >= 1f) animating = false;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float easeOutCubic(float t) {
		final float u = 1f - t;
		return 1f - u * u * u;
	}

	private float currentCornerRadius() {
		// Clamp so a small reticle (after lock-on to a tiny QR) doesn't get
		// over-rounded corners that swallow the brackets.
		return Math.min(reticleRadius, Math.min(reticle.width(), reticle.height()) / 4f);
	}

	private void drawCornerBrackets(Canvas c) {
		final float l = reticle.left;
		final float t = reticle.top;
		final float r = reticle.right;
		final float b = reticle.bottom;
		final float radius = currentCornerRadius();
		final float k = Math.min(cornerLen, Math.min(reticle.width(), reticle.height()) / 2f);

		c.drawLine(l, t + k, l, t + radius, cornerPaint);
		c.drawArc(l, t, l + radius * 2, t + radius * 2, 180, 90, false, cornerPaint);
		c.drawLine(l + radius, t, l + k, t, cornerPaint);

		c.drawLine(r - k, t, r - radius, t, cornerPaint);
		c.drawArc(r - radius * 2, t, r, t + radius * 2, 270, 90, false, cornerPaint);
		c.drawLine(r, t + radius, r, t + k, cornerPaint);

		c.drawLine(r, b - k, r, b - radius, cornerPaint);
		c.drawArc(r - radius * 2, b - radius * 2, r, b, 0, 90, false, cornerPaint);
		c.drawLine(r - radius, b, r - k, b, cornerPaint);

		c.drawLine(l + k, b, l + radius, b, cornerPaint);
		c.drawArc(l, b - radius * 2, l + radius * 2, b, 90, 90, false, cornerPaint);
		c.drawLine(l, b - radius, l, b - k, cornerPaint);
	}

	private void drawScanLineSweep(Canvas c) {
		final long elapsed = SystemClock.uptimeMillis() - startMs;
		final float period = 2400f;
		final float t = (elapsed % period) / period;
		final float u = (t < 0.5f) ? (t * 2f) : (2f - t * 2f);
		final float y = reticle.top + (reticle.height() - 1) * u;

		final int alpha = (int) (220 * (1f - Math.abs(u - 0.5f) * 0.4f));
		scanLinePaint.setAlpha(alpha);
		final float margin = Math.min(cornerLen, reticle.width() / 4f);
		c.drawLine(reticle.left + margin, y, reticle.right - margin, y, scanLinePaint);
	}

	/**
	 * Map a set of points in pre-rotation image coordinates to a padded
	 * bounding rectangle in this view's coordinates. Assumes the camera
	 * preview underneath uses {@code PreviewView.ScaleType.FILL_CENTER} (the
	 * default).
	 *
	 * @param imageWidth        sensor image width  (pre-rotation)
	 * @param imageHeight       sensor image height (pre-rotation)
	 * @param rotationDegrees   0 / 90 / 180 / 270 — how CameraX will rotate
	 *                          the image for display
	 * @param xs                point X coords, image space
	 * @param ys                point Y coords, image space
	 * @return view-space bounding box, padded so brackets sit just outside
	 *         the QR's edges
	 */
	public RectF imageRectToView(int imageWidth, int imageHeight, int rotationDegrees,
	                             float[] xs, float[] ys) {
		// Brackets sit slightly outside the finder patterns to match the QR's true edges.
		final float pad = 20f * density;
		final float[] b = PreviewGeometry.boundsForPoints(
				imageWidth, imageHeight, rotationDegrees,
				getWidth(), getHeight(), pad, xs, ys);
		// Degenerate input — empty / mismatched / null points, or a view not laid
		// out yet. Fall back to the centered reticle rather than drawing a
		// Float.MAX_VALUE box. Happens with some 1D-style ZXing results.
		if (b == null) return new RectF(defaultReticle);
		return new RectF(b[0], b[1], b[2], b[3]);
	}
}
