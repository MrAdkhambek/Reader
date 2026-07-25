/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.dg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.adkhambek.reader.common.mrz.PhotoFormat;

import org.junit.Test;

public class Dg2Test {

	private static byte[] hex(String s) {
		final String c = s.replace(" ", "");
		final int n = c.length() / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; ++i) {
			out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	@Test public void extract_5F2E_jpeg() {
		// 5F2E (primitive) carrying a JPEG SOI.
		final Dg2.Image img = Dg2.extract(hex("5F2E 06 FFD8FF 000000"));
		assertEquals(PhotoFormat.JPEG, img.format);
		assertArrayEquals(hex("FFD8FF 000000"), img.data);
	}

	/**
	 * Regression for the 7F2E variant: 7F2E is a "constructed" tag in BER (its
	 * first byte has bit 0x20 set), so the generic primitive walk recurses into
	 * the raw image bytes and throws. extract() must catch that and still find
	 * the image instead of aborting the whole eMRTD read.
	 */
	@Test public void extract_7F2E_doesNotThrow_andFindsJpeg() {
		final Dg2.Image img = Dg2.extract(hex("7F2E 06 FFD8FF 000000"));
		assertEquals(PhotoFormat.JPEG, img.format);
		assertArrayEquals(hex("FFD8FF 000000"), img.data);
	}

	@Test public void extract_5F2E_jp2Codestream() {
		final Dg2.Image img = Dg2.extract(hex("5F2E 06 FF4FFF51 0000"));
		assertEquals(PhotoFormat.JP2, img.format);
	}

	@Test public void extract_noImage_returnsUnknownWithNullData() {
		final Dg2.Image img = Dg2.extract(hex("5F2E 04 DEADBEEF"));
		assertEquals(PhotoFormat.UNKNOWN, img.format);
		assertNull(img.data);
	}
}
