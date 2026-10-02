/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.mrz;

import androidx.annotation.RestrictTo;

import java.util.Calendar;
import java.util.Locale;

/**
 * Decoder and check-digit validator for an ICAO 9303 machine-readable zone
 * (TD1: 3×30 = 90 characters, TD2: 2×36 = 72, TD3: 2×44 = 88).
 *
 * <p>Whitespace is ignored everywhere, so an MRZ scanned from a QR code with
 * line breaks between its lines decodes as is.
 */
public final class Mrz {
	private Mrz() {
	}

	/**
	 * Verify the check digits of the document number, date of birth and date of
	 * expiry. A random string of the right length decodes without error, so gate
	 * untrusted input (a scanned QR code) on this before calling {@link #decode}.
	 *
	 * @return false for null, an unsupported length, illegal characters, or a bad check digit
	 */
	public static boolean isValid(String mrz) {
		if (mrz == null) return false;
		final String m = compact(mrz);
		switch (m.length()) {
			case 90: return validateTd1(m);
			case 72: return validateTd2(m);
			case 88: return validateTd3(m);
			default: return false;
		}
	}

	/**
	 * Split an MRZ into its fields. Does not check check digits — see {@link #isValid}.
	 *
	 * @throws MrzFormatException if the length matches no supported format
	 */
	public static MrzDocument decode(String mrz) throws MrzFormatException {
		if (mrz == null) throw new MrzFormatException("mrz is null");
		final String m = compact(mrz);
		if (m.length() != 90 && m.length() != 72 && m.length() != 88) {
			throw new MrzFormatException("unsupported MRZ length: " + m.length());
		}
		final MrzDocument.Builder b = new MrzDocument.Builder();
		decodeInto(m, b);
		return b.build();
	}

	/** Decode into an existing builder; the eMRTD reader merges DG1 with other groups. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void decodeInto(String mrz, MrzDocument.Builder out) {
		decodeInto(mrz, out, Calendar.getInstance().get(Calendar.YEAR));
	}

	/** As above, with "now" injected: the century of a birth date pivots on it. */
	static void decodeInto(String mrz, MrzDocument.Builder out, int currentYear) {
		final String m = compact(mrz);
		out.raw(m);
		switch (m.length()) {
			case 90: decodeTd1(m, out, currentYear); break;
			case 72: decodeTd2(m, out, currentYear); break;
			case 88: decodeTd3(m, out, currentYear); break;
			default: // not an MRZ: leave everything but raw unset
		}
	}

	private static String compact(String s) {
		final StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (!Character.isWhitespace(c)) sb.append(c);
		}
		return sb.toString();
	}

	private static boolean validateTd1(String m) {
		// Document number line1[5..14) check at 14; DOB line2[0..6) check at 36;
		// expiry line2[8..14) check at 44.
		return checkDigit(m, 5, 9, 14)
				&& checkDigit(m, 30, 6, 36)
				&& checkDigit(m, 38, 6, 44);
	}

	private static boolean validateTd2(String m) {
		// Document number line2[0..9) check at 45; DOB check at 55; expiry check at 63.
		return checkDigit(m, 36, 9, 45)
				&& checkDigit(m, 49, 6, 55)
				&& checkDigit(m, 57, 6, 63);
	}

	private static boolean validateTd3(String m) {
		// Document number line2[0..9) check at 53; DOB check at 63; expiry check at 71.
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

	private static void decodeTd1(String m, MrzDocument.Builder out, int currentYear) {
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
				.dateOfBirth(formatDate(l2.substring(0, 6), false, currentYear))
				.dateOfExpiry(formatDate(l2.substring(8, 14), true, currentYear));

		final String optional1 = clean(l1.substring(15, 30));
		if (!optional1.isEmpty()) out.optionalData(optional1);
	}

	private static void decodeTd2(String m, MrzDocument.Builder out, int currentYear) {
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
				.dateOfBirth(formatDate(l2.substring(13, 19), false, currentYear))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true, currentYear));
	}

	private static void decodeTd3(String m, MrzDocument.Builder out, int currentYear) {
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
				.dateOfBirth(formatDate(l2.substring(13, 19), false, currentYear))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true, currentYear));

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

	/** {@code YYMMDD} → {@code YYYY-MM-DD}. A birth year after the current two-digit year is in the 1900s; expiry always 2000s. */
	private static String formatDate(String yymmdd, boolean isExpiry, int currentYear) {
		if (yymmdd.length() != 6) return yymmdd;
		try {
			final int yy = Integer.parseInt(yymmdd.substring(0, 2));
			final int mm = Integer.parseInt(yymmdd.substring(2, 4));
			final int dd = Integer.parseInt(yymmdd.substring(4, 6));
			// Reject impossible months/days rather than emitting "1974-99-99".
			if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return yymmdd;
			final int yyyy = (!isExpiry && yy > currentYear % 100) ? yy + 1900 : yy + 2000;
			return String.format(Locale.ROOT, "%04d-%02d-%02d", yyyy, mm, dd);
		} catch (NumberFormatException e) {
			return yymmdd;
		}
	}

	private static String clean(String s) {
		return s.replace('<', ' ').trim();
	}
}
