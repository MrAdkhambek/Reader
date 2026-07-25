/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.mrz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MrzTest {

	@Test public void decodeTd3_passport() {
		// ICAO worked example TD3.
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO7408122F1204159ZE184226B<<<<<10";
		final IdCard c = new IdCard();
		Mrz.decodeMrz(mrz, c);
		assertEquals("P", c.documentType);
		assertEquals("UTO", c.issuingCountry);
		assertEquals("L898902C3", c.documentNumber);
		assertEquals("ERIKSSON", c.lastName);
		assertEquals("ANNA MARIA", c.firstName);
		assertEquals("F", c.sex);
		assertEquals("UTO", c.nationality);
		assertEquals("1974-08-12", c.dateOfBirth);
		assertEquals("2012-04-15", c.dateOfExpiry);
		assertEquals("ZE184226B", c.personalNumber);
	}

	@Test public void decodeTd1_idCard() {
		// 90-char TD1: 3 lines of 30.
		final String mrz =
				"I<UTOD231458907<<<<<<<<<<<<<<<" +
				"7408122F1204159UTO<<<<<<<<<<<6" +
				"ERIKSSON<<ANNA<MARIA<<<<<<<<<<";
		final IdCard c = new IdCard();
		Mrz.decodeMrz(mrz, c);
		assertEquals("I", c.documentType);
		assertEquals("UTO", c.issuingCountry);
		assertEquals("D23145890", c.documentNumber);
		assertEquals("F", c.sex);
		assertEquals("UTO", c.nationality);
		assertEquals("1974-08-12", c.dateOfBirth);
		assertEquals("2012-04-15", c.dateOfExpiry);
		assertEquals("ERIKSSON", c.lastName);
		assertEquals("ANNA MARIA", c.firstName);
	}

	@Test public void decodeMrz_unknownLengthIsNoop() {
		final IdCard c = new IdCard();
		Mrz.decodeMrz("too short", c);
		assertNull(c.documentNumber);
		assertEquals("too short", c.rawMrz);
	}

	/** Birth dates with YY > 50 fall in the 1900s. */
	@Test public void decodeMrz_dobYearAbove50InThe1900s() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO5808122F1204159ZE184226B<<<<<10";
		final IdCard c = new IdCard();
		Mrz.decodeMrz(mrz, c);
		assertEquals("1958-08-12", c.dateOfBirth);
	}

	/** Boundary: YY == 50 is a birth year in the 1900s (born 1950, not 2050). */
	@Test public void decodeMrz_dobYear50InThe1900s() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO5008122F1204159ZE184226B<<<<<10";
		final IdCard c = new IdCard();
		Mrz.decodeMrz(mrz, c);
		assertEquals("1950-08-12", c.dateOfBirth);
	}

	/** Structurally impossible months/days fall back to the raw field rather
	 *  than emitting e.g. "1974-99-99" as if it were a real date. */
	@Test public void decodeMrz_invalidDateFallsBackToRaw() {
		final String mrz =
				"P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
				"L898902C36UTO7499992F1204159ZE184226B<<<<<10";
		final IdCard c = new IdCard();
		Mrz.decodeMrz(mrz, c);
		assertEquals("749999", c.dateOfBirth);
	}
}
