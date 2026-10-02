/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

/**
 * One decoded QR code, with enough to draw it over the camera preview: the
 * finder-pattern positions ZXing returned (image space, before rotation), the
 * image size, and the rotation CameraX applies for display. A QR code always
 * has at least three finder patterns, so {@code xs}/{@code ys} are non-empty.
 */
public record QrCode(
		String text,
		float[] xs,
		float[] ys,
		int imageWidth,
		int imageHeight,
		int rotationDegrees
) {
}
