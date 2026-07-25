/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.mrz;

import java.util.Locale;

/**
 * Decoder for a raw ICAO 9303 machine-readable zone
 * (TD1: 3×30 chars / 90 total, TD2: 2×36 / 72 total, TD3: 2×44 / 88 total).
 *
 * <p>Pure string handling — no Android or APDU dependency, so it is equally
 * usable on an MRZ read off a chip (DG1) and one scanned from a QR code.
 * Unwrapping the DG1 5F1F TLV lives in the NFC module's {@code DgParser}.
 */
public final class Mrz {
	private Mrz() {
	}

	/**
	 * Decode an MRZ into a fresh {@link IdCard}.
	 *
	 * <p>Does not validate — an unrecognised length yields a card carrying only
	 * {@link IdCard#rawMrz()}, and a same-length string of noise yields populated
	 * but meaningless fields. Gate untrusted input on {@link #isValidMrz} first,
	 * or check {@link IdCard#hasMrz()} on the result.
	 */
	public static IdCard decode(String mrz) {
		final IdCard.Builder b = new IdCard.Builder();
		decodeMrz(mrz, b);
		return b.build();
	}

	/**
	 * Decode an MRZ into an existing builder. Used by the eMRTD reader, which
	 * merges DG1 with the other data groups before building.
	 */
	public static void decodeMrz(String mrz, IdCard.Builder out) {
		out.rawMrz(mrz);
		switch (mrz.length()) {
			case 90: decodeTd1(mrz, out); break;
			case 72: decodeTd2(mrz, out); break;
			case 88: decodeTd3(mrz, out); break;
			default: // unsupported / not an MRZ
		}
	}

	/**
	 * Verify the ICAO 9303 MRZ check digits at the structurally-required field
	 * positions (document number, date of birth, date of expiry). A random
	 * 72/88/90-character payload will pass {@link #decodeMrz} silently — call
	 * this first when the input is untrusted (e.g. a scanned QR code) to avoid
	 * presenting noise as an ID document.
	 *
	 * <p>Returns {@code false} for any non-MRZ length, illegal characters, or
	 * mismatched check digits.
	 */
	public static boolean isValidMrz(String mrz) {
		if (mrz == null) return false;
		switch (mrz.length()) {
			case 90: return validateTd1(mrz);
			case 72: return validateTd2(mrz);
			case 88: return validateTd3(mrz);
			default: return false;
		}
	}

	private static boolean validateTd1(String m) {
		// TD1 layout: line1[0..30), line2[30..60), line3[60..90).
		// Document number: line1[5..14), check digit at line1[14] (abs index 14).
		// DOB:             line2[0..6),  check digit at line2[6]  (abs index 36).
		// Expiry:          line2[8..14), check digit at line2[14] (abs index 44).
		return checkDigit(m, 5, 9, 14)
				&& checkDigit(m, 30, 6, 36)
				&& checkDigit(m, 38, 6, 44);
	}

	private static boolean validateTd2(String m) {
		// TD2 layout: line1[0..36), line2[36..72).
		// Document number: line2[0..9), check digit at line2[9]  (abs index 45).
		// DOB:             line2[13..19), check at line2[19]     (abs index 55).
		// Expiry:          line2[21..27), check at line2[27]     (abs index 63).
		return checkDigit(m, 36, 9, 45)
				&& checkDigit(m, 49, 6, 55)
				&& checkDigit(m, 57, 6, 63);
	}

	private static boolean validateTd3(String m) {
		// TD3 layout: line1[0..44), line2[44..88).
		// Document number: line2[0..9), check at line2[9]    (abs index 53).
		// DOB:             line2[13..19), check at line2[19] (abs index 63).
		// Expiry:          line2[21..27), check at line2[27] (abs index 71).
		return checkDigit(m, 44, 9, 53)
				&& checkDigit(m, 57, 6, 63)
				&& checkDigit(m, 65, 6, 71);
	}

	/** ICAO 9303 mod-10 with weights 7-3-1. '<' → 0, '0'-'9' → 0-9, 'A'-'Z' → 10-35. */
	private static boolean checkDigit(String s, int start, int len, int checkPos) {
		if (checkPos >= s.length()) return false;
		final int[] weights = {7, 3, 1};
		int sum = 0;
		for (int i = 0; i < len; ++i) {
			final char c = s.charAt(start + i);
			final int v;
			if (c >= '0' && c <= '9') v = c - '0';
			else if (c >= 'A' && c <= 'Z') v = c - 'A' + 10;
			else if (c == '<') v = 0;
			else return false;
			sum += v * weights[i % 3];
		}
		final int expect = sum % 10;
		final char ck = s.charAt(checkPos);
		if (ck == '<') return expect == 0;
		if (ck < '0' || ck > '9') return false;
		return expect == (ck - '0');
	}

	private static void decodeTd1(String m, IdCard.Builder out) {
		final String l1 = m.substring(0, 30);
		final String l2 = m.substring(30, 60);
		final String l3 = m.substring(60, 90);
		final String[] names = splitName(l3);

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l1.substring(5, 14)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(7)))
				.nationality(clean(l2.substring(15, 18)))
				.dateOfBirth(formatDate(l2.substring(0, 6), false))
				.dateOfExpiry(formatDate(l2.substring(8, 14), true));

		final String optional1 = clean(l1.substring(15, 30));
		if (!optional1.isEmpty()) out.optionalData(optional1);
	}

	private static void decodeTd2(String m, IdCard.Builder out) {
		final String l1 = m.substring(0, 36);
		final String l2 = m.substring(36, 72);
		final String[] names = splitName(l1.substring(5, 36));

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l2.substring(0, 9)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(20)))
				.nationality(clean(l2.substring(10, 13)))
				.dateOfBirth(formatDate(l2.substring(13, 19), false))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true));
	}

	private static void decodeTd3(String m, IdCard.Builder out) {
		final String l1 = m.substring(0, 44);
		final String l2 = m.substring(44, 88);
		final String[] names = splitName(l1.substring(5, 44));

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l2.substring(0, 9)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(20)))
				.nationality(clean(l2.substring(10, 13)))
				.dateOfBirth(formatDate(l2.substring(13, 19), false))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true));

		final String personalNr = clean(l2.substring(28, 42));
		if (!personalNr.isEmpty()) out.personalNumber(personalNr);
	}

	private static String[] splitName(String namePart) {
		final int sep = namePart.indexOf("<<");
		final String surname = (sep >= 0) ? namePart.substring(0, sep) : namePart;
		final String given = (sep >= 0) ? namePart.substring(sep + 2) : "";
		return new String[]{clean(surname), clean(given)};
	}

	private static String decodeSex(char c) {
		switch (c) {
			case 'M': return "M";
			case 'F': return "F";
			default: return null;
		}
	}

	/** {@code YYMMDD} → {@code YYYY-MM-DD}. Birth dates ≥ 50 land in the 1900s; expiry dates always 2000s. */
	private static String formatDate(String yymmdd, boolean isExpiry) {
		if (yymmdd.length() != 6) return yymmdd;
		try {
			final int yy = Integer.parseInt(yymmdd.substring(0, 2));
			final int mm = Integer.parseInt(yymmdd.substring(2, 4));
			final int dd = Integer.parseInt(yymmdd.substring(4, 6));
			// Reject structurally impossible months/days rather than emitting
			// e.g. "1974-99-99" as if it were a real date.
			if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return yymmdd;
			final int yyyy = (!isExpiry && yy >= 50) ? yy + 1900 : yy + 2000;
			return String.format(Locale.ROOT, "%04d-%02d-%02d", yyyy, mm, dd);
		} catch (NumberFormatException e) {
			return yymmdd;
		}
	}

	private static String clean(String s) {
		return s.replace('<', ' ').trim();
	}
}
