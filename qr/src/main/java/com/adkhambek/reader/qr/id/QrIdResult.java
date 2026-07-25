/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.id;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.adkhambek.reader.common.mrz.IdCard;

/**
 * Output of {@link QrIdScannerView}: the raw scanned text plus, when the QR
 * content was an ICAO 9303 MRZ (TD1 / TD2 / TD3), the parsed {@link IdCard}.
 *
 * <p>{@link #mrz} is {@code null} when the scanned text wasn't recognized as MRZ.
 */
public final class QrIdResult {
	@NonNull public final String text;
	@Nullable public final IdCard mrz;

	public QrIdResult(@NonNull String text, @Nullable IdCard mrz) {
		this.text = text;
		this.mrz = mrz;
	}

	public boolean isMrz() {
		return mrz != null;
	}
}
