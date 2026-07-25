/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.dg;

import com.adkhambek.reader.common.mrz.PhotoFormat;
import com.adkhambek.reader.nfc.tech.Iso7816;

/**
 * DG2 (ICAO 9303) — facial biometric. The actual image lives inside a
 * Biometric Data Block (5F2E or its proper-ICAO sibling 7F2E). We look for the
 * embedded JPEG/JP2 magic and slice from there.
 */
public final class Dg2 {

	private Dg2() {
	}

	public static final class Image {
		public final PhotoFormat format;
		public final byte[] data;

		Image(PhotoFormat format, byte[] data) {
			this.format = format;
			this.data = data;
		}
	}

	public static Image extract(byte[] dg2) {
		byte[] data = dg2;
		try {
			final Iso7816.BerHouse house = Iso7816.BerHouse.from(dg2);
			Iso7816.BerTLV bdb = house.findFirst(0x5F2E);
			if (bdb == null) bdb = house.findFirst(0x7F2E);
			if (bdb != null) data = bdb.v.getBytes();
		} catch (RuntimeException malformed) {
			// 7F2E is a "constructed" tag per BER (its first byte has bit 0x20
			// set), so the recursive primitive walk in BerHouse.from descends into
			// its value — but that value is the raw CBEFF block + image bytes, not
			// valid TLV, so parsing throws. Rather than fail the whole eMRTD read
			// (which would discard already-decoded DG1/DG11-13), fall back to
			// scanning the entire DG2 record for the embedded image magic.
			data = dg2;
		}
		return scanForImage(data);
	}

	/** Locate an embedded JPEG / JPEG2000 image by its magic bytes. Each check is
	 *  individually bounds-guarded so a small block (or a magic near the end) is
	 *  still found without risking an out-of-bounds read. */
	private static Image scanForImage(byte[] data) {
		for (int i = 0; i < data.length; ++i) {
			// JPEG SOI: FF D8 FF
			if (i + 2 < data.length
					&& (data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8 && (data[i + 2] & 0xFF) == 0xFF) {
				return new Image(PhotoFormat.JPEG, slice(data, i, data.length - i));
			}
			// JP2 with full signature box: 00 00 00 0C 6A 50 ...
			if (i + 5 < data.length
					&& data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x00 && data[i + 3] == 0x0C
					&& (data[i + 4] & 0xFF) == 0x6A && (data[i + 5] & 0xFF) == 0x50) {
				return new Image(PhotoFormat.JP2, slice(data, i, data.length - i));
			}
			// JP2 codestream: FF 4F FF 51
			if (i + 3 < data.length
					&& (data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0x4F
					&& (data[i + 2] & 0xFF) == 0xFF && (data[i + 3] & 0xFF) == 0x51) {
				return new Image(PhotoFormat.JP2, slice(data, i, data.length - i));
			}
		}
		return new Image(PhotoFormat.UNKNOWN, null);
	}

	private static byte[] slice(byte[] a, int from, int len) {
		final byte[] out = new byte[len];
		System.arraycopy(a, from, out, 0, len);
		return out;
	}
}
