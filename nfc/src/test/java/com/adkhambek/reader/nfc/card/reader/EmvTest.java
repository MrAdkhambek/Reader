/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.card.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.adkhambek.reader.nfc.card.Currency;
import com.adkhambek.reader.iso7816.Iso7816.BerHouse;
import com.adkhambek.reader.iso7816.Iso7816.BerTLV;
import com.adkhambek.reader.iso7816.Iso7816.BerV;

import org.junit.Test;

public class EmvTest {

	private static byte[] hex(String s) {
		final String c = s.replace(" ", "");
		final int n = c.length() / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; ++i) {
			out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static BerHouse house(String tlvHex) {
		final BerHouse h = new BerHouse();
		BerTLV.extractPrimitives(h, new BerV(hex(tlvHex)));
		return h;
	}

	// --- ymFromBcd / country: regression for H3 -----------------------------

	@Test public void ymFromBcd_lowBytes() {
		// 5F25 03 26 01 01 → effective date 2026-01-01, ym = "2026.01"
		assertEquals("2026.01", EMV.ymFromBcd(house("5F25 03 26 01 01"), (short) 0x5F25));
	}

	/** Regression for H3: bytes >= 0x80 must not sign-extend into a long "FFFFFF80" string. */
	@Test public void ymFromBcd_highBitYearStaysTwoDigits() {
		// Year byte 0x80, month 0x12 — pre-fix this would emit "20FFFFFF80.FFFFFF12"
		assertEquals("2080.12", EMV.ymFromBcd(house("5F25 03 80 12 25"), (short) 0x5F25));
	}

	@Test public void country_lowBytes() {
		// 5F28 02 08 40 → "0840" (US numeric ISO-3166)
		assertEquals("0840", EMV.country(house("5F28 02 08 40")));
	}

	/** Regression for H3: high-bit country bytes must format as 2 hex chars each. */
	@Test public void country_highBitBytesStayFourChars() {
		assertEquals("80FF", EMV.country(house("5F28 02 80 FF")));
	}

	// --- currency -----------------------------------------------------------

	@Test public void currency_usd() {
		assertEquals(Currency.USD, EMV.currency(house("9F42 02 08 40")));
	}

	@Test public void currency_eur() {
		assertEquals(Currency.EUR, EMV.currency(house("9F42 02 09 78")));
	}

	@Test public void currency_uzs() {
		assertEquals(Currency.UZS, EMV.currency(house("9F42 02 08 60")));
	}

	@Test public void currency_unmappedReturnsNull() {
		assertNull(EMV.currency(house("9F42 02 99 99")));
	}

	// --- identify (AID labels, regression for L1) --------------------------

	@Test public void identify_visa() {
		assertEquals("Visa", EMV.identify(hex("A0 00 00 00 03 10 10")));
	}

	@Test public void identify_mastercard() {
		assertEquals("Mastercard", EMV.identify(hex("A0 00 00 00 04 10 10")));
	}

	@Test public void identify_unionpay() {
		assertEquals("UnionPay", EMV.identify(hex("A0 00 00 03 33 01 01 01")));
	}

	@Test public void identify_unknown() {
		assertEquals("Unknown", EMV.identify(hex("DE AD BE EF")));
	}

	// --- extractPan / extractExpiry ----------------------------------------

	@Test public void extractPan_fromTrack2_splitsOnD() {
		// 57 0D 12 34 56 78 90 12 34 56 D2 51 21 01 FF
		// Track 2 hex: PAN(16) + D + EXPIRY(YYMM)(4) + SVC(3) + FILLER(1) + F-pad
		final BerHouse h = house("57 0D 12 34 56 78 90 12 34 56 D2 51 21 01 FF");
		assertEquals("1234567890123456", EMV.extractPan(h));
	}

	@Test public void extractPan_fromTag5A_stripsFPadding() {
		// 5A 0A 12 34 56 78 90 12 34 56 FF FF — 16-digit PAN with F-padding
		final BerHouse h = house("5A 0A 12 34 56 78 90 12 34 56 FF FF");
		assertEquals("1234567890123456", EMV.extractPan(h));
	}

	@Test public void extractExpiry_fromTrack2() {
		final BerHouse h = house("57 0D 12 34 56 78 90 12 34 56 D2 51 21 01 FF");
		assertEquals("2025.12", EMV.extractExpiry(h));
	}

	@Test public void extractExpiry_fromTag5F24() {
		// 5F24 03 26 12 31 → 2026.12
		assertEquals("2026.12", EMV.extractExpiry(house("5F24 03 26 12 31")));
	}

	// --- GPO Format-1 AFL parsing ------------------------------------------

	@Test public void aflFromFormat1_shortForm() {
		// 80 0E <AIP 2> <AFL 12 = three 4-byte entries>
		assertArrayEquals(
				hex("08010100 10010301 20010100"),
				EMV.aflFromFormat1(hex("80 0E 3C00 08010100 10010301 20010100")));
	}

	/** A spec-legal long-form length (80 81 nn) must not be misread as len=0x81,
	 *  which previously dropped the AFL and rejected a valid card. */
	@Test public void aflFromFormat1_longForm() {
		assertArrayEquals(
				hex("08010100 10010301 20010100"),
				EMV.aflFromFormat1(hex("80 81 0E 3C00 08010100 10010301 20010100")));
	}

	@Test public void aflFromFormat1_notFormat1ReturnsNull() {
		assertNull(EMV.aflFromFormat1(hex("77 0E 3C00 08010100 10010301 20010100")));
	}
}
