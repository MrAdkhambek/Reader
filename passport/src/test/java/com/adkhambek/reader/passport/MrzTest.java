/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class MrzTest {

	/** ICAO worked example, 88 chars (2×44). */
	private static final String TD3 =
			"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
			"L898902C36UTO7408122F1204159ZE184226B<<<<<10";

	/** 90 chars (3×30). */
	private static final String TD1 =
			"I<UTOD231458907<<<<<<<<<<<<<<<" +
			"7408122F1204159UTO<<<<<<<<<<<6" +
			"ERIKSSON<<ANNA<MARIA<<<<<<<<<<";

	/** 72 chars (2×36). Same person/dates as the TD1 fixture, in TD2 layout. */
	private static final String TD2 =
			"I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<" +
			"D231458907UTO7408122F1204159<<<<<<<0";

	@Test public void decodeTd3_passport() throws MrzFormatException {
		// ICAO worked example TD3.
		final String mrz = TD3;
		final MrzDocument c = Mrz.decode(mrz);
		assertEquals("P", c.documentType());
		assertEquals("UTO", c.issuingCountry());
		assertEquals("L898902C3", c.documentNumber());
		assertEquals("ERIKSSON", c.lastName());
		assertEquals("ANNA MARIA", c.firstName());
		assertEquals("F", c.sex());
		assertEquals("UTO", c.nationality());
		assertEquals("1974-08-12", c.dateOfBirth());
		assertEquals("2012-04-15", c.dateOfExpiry());
		assertEquals("ZE184226B", c.personalNumber());
	}

	@Test public void decodeTd1_idCard() throws MrzFormatException {
		// 90-char TD1: 3 lines of 30.
		final String mrz = TD1;
		final MrzDocument c = Mrz.decode(mrz);
		assertEquals("I", c.documentType());
		assertEquals("UTO", c.issuingCountry());
		assertEquals("D23145890", c.documentNumber());
		assertEquals("F", c.sex());
		assertEquals("UTO", c.nationality());
		assertEquals("1974-08-12", c.dateOfBirth());
		assertEquals("2012-04-15", c.dateOfExpiry());
		assertEquals("ERIKSSON", c.lastName());
		assertEquals("ANNA MARIA", c.firstName());
	}

	/** decode() throws on input it cannot parse, rather than returning a husk. */
	@Test public void decode_throwsOnUnknownLength() {
		try {
			Mrz.decode("too short");
			fail("expected MrzFormatException");
		} catch (MrzFormatException expected) {
		}
	}

	/** Birth dates with YY > 50 fall in the 1900s. */
	@Test public void decodeMrz_dobYearAbove50InThe1900s() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO5808122F1204159ZE184226B<<<<<10";
		final MrzDocument c = Mrz.decode(mrz);
		assertEquals("1958-08-12", c.dateOfBirth());
	}

	/** Boundary: YY == 50 is a birth year in the 1900s (born 1950, not 2050). */
	@Test public void decodeMrz_dobYear50InThe1900s() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO5008122F1204159ZE184226B<<<<<10";
		final MrzDocument c = Mrz.decode(mrz);
		assertEquals("1950-08-12", c.dateOfBirth());
	}

	/** Structurally impossible months/days fall back to the raw field rather
	 *  than emitting e.g. "1974-99-99" as if it were a real date. */
	@Test public void decodeMrz_invalidDateFallsBackToRaw() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO7499992F1204159ZE184226B<<<<<10";
		final MrzDocument c = Mrz.decode(mrz);
		assertEquals("749999", c.dateOfBirth());
	}

	// --- TD2 -------------------------------------------------------------------

	/** TD2 (72 chars) — the third format the library claims to support. */
	@Test public void decodeTd2_travelDocument() {
		final MrzDocument c = Mrz.decode(TD2);
		assertEquals("I", c.documentType());
		assertEquals("UTO", c.issuingCountry());
		assertEquals("D23145890", c.documentNumber());
		assertEquals("ERIKSSON", c.lastName());
		assertEquals("ANNA MARIA", c.firstName());
		assertEquals("F", c.sex());
		assertEquals("UTO", c.nationality());
		assertEquals("1974-08-12", c.dateOfBirth());
		assertEquals("2012-04-15", c.dateOfExpiry());
	}

	// --- isValidMrz: the check-digit gate on untrusted (scanned) input ---------

	@Test public void isValidMrz_acceptsAllThreeFormats() {
		assertTrue(Mrz.isValid(TD1));
		assertTrue(Mrz.isValid(TD2));
		assertTrue(Mrz.isValid(TD3));
	}

	@Test public void isValidMrz_rejectsNullAndWrongLength() {
		assertFalse(Mrz.isValid(null));
		assertFalse(Mrz.isValid(""));
		assertFalse(Mrz.isValid("NOT AN MRZ"));
		// 89 chars: one short of TD3, one over TD1's 88... neither length is an MRZ.
		assertFalse(Mrz.isValid(TD3 + "X"));
	}

	/**
	 * The point of the gate: a payload of the right *length* but wrong content
	 * must not pass. Without the check digits, {@code decodeMrz} happily fills in
	 * fields from arbitrary 88-character noise.
	 */
	@Test public void isValidMrz_rejectsRightLengthNoise() {
		final StringBuilder noise = new StringBuilder();
		for (int i = 0; i < 88; ++i) noise.append('A');
		assertFalse(Mrz.isValid(noise.toString()));
		assertEquals(88, noise.length());
	}

	/** Flipping any one of the three check digits must fail validation. */
	@Test public void isValidMrz_rejectsCorruptedCheckDigits() {
		// TD3 check digits sit at absolute indices 53 (doc no.), 63 (DOB), 71 (expiry).
		assertFalse(Mrz.isValid(bump(TD3, 53)));
		assertFalse(Mrz.isValid(bump(TD3, 63)));
		assertFalse(Mrz.isValid(bump(TD3, 71)));
	}

	/** A corrupted *field* is caught too — that's what the check digit is for. */
	@Test public void isValidMrz_rejectsCorruptedField() {
		// Index 44 is the first character of the TD3 document number.
		assertFalse(Mrz.isValid(bump(TD3, 44)));
	}

	/** Characters outside 0-9 / A-Z / '<' aren't MRZ characters at all. */
	@Test public void isValidMrz_rejectsIllegalCharacters() {
		final char[] cs = TD3.toCharArray();
		cs[45] = '*';
		assertFalse(Mrz.isValid(new String(cs)));
	}

	/** A valid MRZ that passes the gate still decodes to a populated card. */
	@Test public void isValidMrz_gatedDecodeYieldsCard() {
		assertTrue(Mrz.isValid(TD3));
		assertTrue(Mrz.decode(TD3).hasMrz());
	}

	// --- Whitespace: MRZs scanned from QR codes carry line breaks ----------

	/** Review focus: whitespace is never an MRZ character, so it is ignored. */
	@Test public void isValid_ignoresLineBreaks() {
		assertTrue(Mrz.isValid(TD3.substring(0, 44) + "\n" + TD3.substring(44) + "\n"));
	}

	@Test public void decode_ignoresLineBreaks() {
		assertEquals("L898902C3",
				Mrz.decode(TD3.substring(0, 44) + "\r\n" + TD3.substring(44)).documentNumber());
	}

	/** Replace the character at {@code index} with a different one. */
	private static String bump(String s, int index) {
		final char[] cs = s.toCharArray();
		cs[index] = (cs[index] == '0') ? '1' : '0';
		return new String(cs);
	}
}
