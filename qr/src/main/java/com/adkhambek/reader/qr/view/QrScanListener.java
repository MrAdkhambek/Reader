/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.view;

import com.adkhambek.reader.qr.common.ScanResult;

import com.adkhambek.reader.common.Result;

/**
 * Typed callback for {@link QrScanner}. Replaces the legacy
 * {@code onScanEvent(EVENT, Object...)} varargs API with a {@link Result}-based
 * terminal event.
 */
public interface QrScanListener {
	/** Camera preview has started; no scan result yet. */
	default void onScanning() {
	}

	/**
	 * A decoded {@link ScanResult} or a camera-binding error.
	 *
	 * <p><b>Errors are limited to CameraX init / bindToLifecycle failures.</b>
	 * Per-frame analyzer exceptions (no QR found, malformed YUV, intermittent
	 * decode failures) are logged but never delivered here — they are not
	 * "errors" in the user-facing sense; another frame is on its way.
	 */
	void onResult(Result<ScanResult, Throwable> result);
}
