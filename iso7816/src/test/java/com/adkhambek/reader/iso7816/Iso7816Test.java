/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.adkhambek.reader.iso7816.Iso7816.BerHouse;
import com.adkhambek.reader.iso7816.Iso7816.BerL;
import com.adkhambek.reader.iso7816.Iso7816.BerT;
import com.adkhambek.reader.iso7816.Iso7816.BerTLV;
import com.adkhambek.reader.iso7816.Iso7816.BerV;
import com.adkhambek.reader.iso7816.Iso7816.Response;

import org.junit.Test;

public class Iso7816Test {

	private static byte[] hex(String s) {
		final int n = s.length() / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; ++i) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static BerHouse parseAll(byte[] payload) {
		final BerHouse house = new BerHouse();
		BerTLV.extractPrimitives(house, new BerV(payload));
		return house;
	}

	// --- BerL.calc ---------------------------------------------------------

	@Test public void berL_shortForm() {
		assertEquals(0x05, BerL.calc(hex("05"), 0));
		assertEquals(0x7F, BerL.calc(hex("7F"), 0));
	}

	@Test public void berL_longForm_oneByte() {
		assertEquals(0x80, BerL.calc(hex("8180"), 0));
		assertEquals(0xFF, BerL.calc(hex("81FF"), 0));
	}

	@Test public void berL_longForm_twoBytes() {
		assertEquals(0x0100, BerL.calc(hex("820100"), 0));
		assertEquals(0xFFFF, BerL.calc(hex("82FFFF"), 0));
	}

	/** Regression: calc must work at non-zero start. */
	@Test public void berL_longForm_nonZeroStart() {
		assertEquals(0x01FF, BerL.calc(hex("AAAAAA8201FF"), 3));
	}

	@Test public void berL_test_lengths() {
		assertEquals(1, BerL.test(hex("05"), 0));
		assertEquals(2, BerL.test(hex("8105"), 0));
		assertEquals(3, BerL.test(hex("820105"), 0));
	}

	@Test public void berL_test_rejectsTruncated() {
		try {
			BerL.test(hex("8201"), 0);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	// --- BerT.test ---------------------------------------------------------

	@Test public void berT_singleByte() {
		assertEquals(1, BerT.test(hex("4F"), 0));
		assertEquals(1, BerT.test(hex("57"), 0));
	}

	@Test public void berT_twoByte() {
		assertEquals(2, BerT.test(hex("9F38"), 0));
		assertEquals(2, BerT.test(hex("5F2A"), 0));
	}

	/** Regression: truncated multi-byte tag must throw, not over-read. */
	@Test public void berT_rejectsTruncatedMultiByte() {
		try {
			BerT.test(hex("9F80"), 0);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	// --- TLV extraction ----------------------------------------------------

	@Test public void tlv_extract_findsPrimitivesInsideConstructed() {
		// 6F 1A FCI Template (constructed)
		//   84 0E "2PAY.SYS.DDF01"
		//   A5 08 FCI Proprietary Template
		//     BF0C 05 FCI Issuer Discretionary Data
		//       61 03 App Template
		//         4F 01 AA
		final byte[] payload = hex("6F1A840E325041592E5359532E4444463031A508BF0C0561034F01AA");
		final BerHouse house = parseAll(payload);
		assertNotNull(house.findFirst((byte) 0x4F));
		assertEquals(1, house.findAll((byte) 0x4F).size());
		assertArrayEquals(new byte[]{(byte) 0xAA}, house.findFirst((byte) 0x4F).v.getBytes());
		assertNotNull(house.findFirst((byte) 0x84));
	}

	@Test public void tlv_extract_skipsZeroAndFFPadding() {
		final byte[] payload = hex("00004F02ABCDFFFF");
		final BerHouse house = parseAll(payload);
		assertNotNull(house.findFirst((byte) 0x4F));
		assertArrayEquals(hex("ABCD"), house.findFirst((byte) 0x4F).v.getBytes());
	}

	@Test public void tlv_read_roundTripsTagAndValue() {
		final byte[] payload = hex("9F38020001");
		final BerTLV tlv = BerTLV.read(payload, 0);
		assertTrue(tlv.t.match((short) 0x9F38));
		assertEquals(2, tlv.l.toInt());
		assertArrayEquals(hex("0001"), tlv.v.getBytes());
	}

	// --- TLV encode (used by Secure Messaging) -----------------------------

	@Test public void tlv_encode_singleByteTagShortLength() {
		assertArrayEquals(hex("8E0801020304050607FF"),
				BerTLV.encode(0x8E, hex("01020304050607FF")));
	}

	@Test public void tlv_encode_twoByteTagShortLength() {
		assertArrayEquals(hex("9F1A020840"),
				BerTLV.encode(0x9F1A, hex("0840")));
	}

	@Test public void tlv_encode_longFormLength_oneByte() {
		final byte[] v = new byte[200];
		final byte[] tlv = BerTLV.encode(0x87, v);
		assertEquals(0x87, tlv[0] & 0xFF);
		assertEquals(0x81, tlv[1] & 0xFF);
		assertEquals(200, tlv[2] & 0xFF);
		assertEquals(203, tlv.length);
	}

	@Test public void tlv_encode_longFormLength_twoBytes() {
		final byte[] v = new byte[300];
		final byte[] tlv = BerTLV.encode(0x87, v);
		assertEquals(0x87, tlv[0] & 0xFF);
		assertEquals(0x82, tlv[1] & 0xFF);
		assertEquals(0x01, tlv[2] & 0xFF);
		assertEquals(0x2C, tlv[3] & 0xFF);
		assertEquals(304, tlv.length);
	}

	// --- BerHouse.from -----------------------------------------------------

	@Test public void berHouse_fromShortcut() {
		final BerHouse h = BerHouse.from(hex("9F38020001"));
		assertNotNull(h.findFirst((short) 0x9F38));
	}

	// --- Response ----------------------------------------------------------

	@Test public void response_okStripsSw() {
		final Response r = new Response(hex("ABCD9000"));
		assertTrue(r.isOkey());
		assertArrayEquals(hex("ABCD"), r.getBytes());
	}

	@Test public void response_errorReturnsEmpty() {
		final Response r = new Response(hex("6F00"));
		assertFalse(r.isOkey());
		assertArrayEquals(new byte[0], r.getBytes());
	}

	@Test public void response_nullDefaultsToError() {
		final Response r = new Response(null);
		assertFalse(r.isOkey());
		assertEquals("0x6F00", r.getSw12String());
	}

	@Test public void response_payloadAlwaysReturnsBytesWithoutSw() {
		final Response r = new Response(hex("AABBCC6A82"));
		assertFalse(r.isOkey());
		assertArrayEquals(hex("AABBCC"), r.getPayload());
	}
}
