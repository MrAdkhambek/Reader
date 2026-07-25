/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.bac;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.adkhambek.reader.common.Hex;

import org.junit.Test;

/**
 * Vectors are from the ICAO 9303 Part 11 BAC worked example
 * (doc number {@code L898902C<}, DOB {@code 690806}, expiry {@code 940623}).
 */
public class BacTest {

	private static final String DOC = "L898902C<";
	private static final String DOB = "690806";
	private static final String EXP = "940623";

	private static byte[] hex(String s) {
		final String c = s.replace(" ", "");
		final int n = c.length() / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; ++i) {
			out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	@Test public void checkDigit_docNumber() {
		assertEquals('3', Bac.checkDigit(DOC));
	}

	@Test public void checkDigit_dob() {
		assertEquals('1', Bac.checkDigit(DOB));
	}

	@Test public void checkDigit_expiry() {
		assertEquals('6', Bac.checkDigit(EXP));
	}

	@Test public void mrzInfo_concatenatesDocCdDobCdExpCd() {
		assertEquals("L898902C<369080619406236", Bac.mrzInfo(DOC, DOB, EXP));
	}

	@Test public void mrzInfo_padsShortDocNumberWithFillers() {
		// 5-char doc number → padded to 9 with '<', and the check digit covers the padded value.
		assertEquals("AB123<<<<", Bac.mrzInfo("AB123", DOB, EXP).substring(0, 9));
	}

	@Test public void kSeed_icaoWorkedExample() {
		assertArrayEquals(hex("239AB9CB282DAF66231DC5A4DF6BFBAE"),
				Bac.kSeed(Bac.mrzInfo(DOC, DOB, EXP)));
	}

	@Test public void deriveKey_kEncFromIcaoWorkedExample() {
		final byte[] kSeed = hex("239AB9CB282DAF66231DC5A4DF6BFBAE");
		assertArrayEquals(hex("AB94FDECF2674FDFB9B391F85D7F76F2"), Bac.deriveKey(kSeed, 1));
	}

	@Test public void deriveKey_kMacFromIcaoWorkedExample() {
		final byte[] kSeed = hex("239AB9CB282DAF66231DC5A4DF6BFBAE");
		assertArrayEquals(hex("7962D9ECE03D1ACD4C76089DCE131543"), Bac.deriveKey(kSeed, 2));
	}

	@Test public void iso9797Mac_icaoWorkedExample_eIfd() {
		// K_MAC and E.IFD from the ICAO 9303 worked example → published M.IFD.
		assertArrayEquals(
				hex("5F1448EEA8AD90A7"),
				Iso9797Mac.mac(
						hex("7962D9ECE03D1ACD4C76089DCE131543"),
						hex("72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2")));
	}

	@Test public void hex_padThenUnpadRoundTrip() {
		final byte[] data = hex("01020304");
		final byte[] padded = Hex.pad(data, 8);
		assertEquals(8, padded.length);
		assertEquals((byte) 0x80, padded[4]);
		assertArrayEquals(data, Hex.unpad(padded));
	}

	@Test public void hex_padAlreadyAtBlockSizeAddsFullBlock() {
		final byte[] data = hex("0102030405060708");
		final byte[] padded = Hex.pad(data, 8);
		assertEquals(16, padded.length);
		assertEquals((byte) 0x80, padded[8]);
	}
}
