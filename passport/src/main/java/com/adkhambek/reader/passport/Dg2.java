/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.util.Arrays;
import java.util.List;

/**
 * DG2 — the facial image, inside a biometric data block (5F2E, or 7F2E). Found
 * by its JPEG / JPEG 2000 magic bytes rather than by parsing CBEFF headers.
 */
final class Dg2 {
	private Dg2() {
	}

	static final class Image {
		final PhotoFormat format;
		final byte[] data;

		Image(PhotoFormat format, byte[] data) {
			this.format = format;
			this.data = data;
		}
	}

	static Image extract(byte[] dg2) {
		byte[] data = dg2;
		try {
			final List<Tlv> tlvs = Tlv.primitives(dg2);
			Tlv bdb = Tlv.find(tlvs, 0x5F2E);
			if (bdb == null) bdb = Tlv.find(tlvs, 0x7F2E);
			if (bdb != null) data = bdb.value;
		} catch (RuntimeException malformed) {
			// 7F2E is constructed in BER, so the walk descends into the raw image
			// bytes and throws. Scan the whole record for the image instead of
			// failing the read and losing DG1 / DG11-13.
			data = dg2;
		}
		return scanForImage(data);
	}

	private static Image scanForImage(byte[] d) {
		for (int i = 0; i < d.length; ++i) {
			// JPEG SOI: FF D8 FF
			if (i + 2 < d.length && (d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8
					&& (d[i + 2] & 0xFF) == 0xFF) {
				return new Image(PhotoFormat.JPEG, Arrays.copyOfRange(d, i, d.length));
			}
			// JP2 signature box: 00 00 00 0C 6A 50
			if (i + 5 < d.length && d[i] == 0x00 && d[i + 1] == 0x00 && d[i + 2] == 0x00
					&& d[i + 3] == 0x0C && (d[i + 4] & 0xFF) == 0x6A && (d[i + 5] & 0xFF) == 0x50) {
				return new Image(PhotoFormat.JP2, Arrays.copyOfRange(d, i, d.length));
			}
			// JP2 codestream: FF 4F FF 51
			if (i + 3 < d.length && (d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0x4F
					&& (d[i + 2] & 0xFF) == 0xFF && (d[i + 3] & 0xFF) == 0x51) {
				return new Image(PhotoFormat.JP2, Arrays.copyOfRange(d, i, d.length));
			}
		}
		return new Image(PhotoFormat.UNKNOWN, null);
	}
}
