/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.dg;

import androidx.annotation.RestrictTo;

import com.adkhambek.reader.passport.DocumentDetails;
import com.adkhambek.reader.passport.Passport;
import com.adkhambek.reader.passport.PersonalDetails;
import com.adkhambek.reader.passport.iso7816.Bytes;
import com.adkhambek.reader.passport.iso7816.Tlv;
import com.adkhambek.reader.passport.mrz.Mrz;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parsers for EF.COM, DG1, DG11, DG12 and DG13. DG2 is {@link Dg2}. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class DgParser {
	private DgParser() {
	}

	/** DG1: the MRZ, in tag 5F1F. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void parseDg1(byte[] dg1, Passport.Builder out) {
		final Tlv mrz = Tlv.find(Tlv.primitives(dg1), 0x5F1F);
		if (mrz != null) Mrz.decodeInto(new String(mrz.value, StandardCharsets.US_ASCII), out.mrz);
	}

	/** EF.COM: the tag list (5C) of data groups present. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static List<String> parseCom(byte[] com) {
		final List<String> out = new ArrayList<>();
		final Tlv list = Tlv.find(Tlv.primitives(com), 0x5C);
		if (list != null) for (final byte b : list.value) out.add(dgName(b & 0xFF));
		return out;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void parseDg11(byte[] dg11, Passport.Builder out) {
		final List<Tlv> t = Tlv.primitives(dg11);
		// 5F10 is a fallback for cards that leave the MRZ field blank — never an overwrite.
		out.mrz.personalNumberIfAbsent(text(t, 0x5F10));
		out.personalDetails = new PersonalDetails(text(t, 0x5F0E), text(t, 0x5F0F),
				text(t, 0x5F11), date(t, 0x5F2B), text(t, 0x5F42), text(t, 0x5F12),
				text(t, 0x5F13), text(t, 0x5F14));
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void parseDg12(byte[] dg12, Passport.Builder out) {
		final List<Tlv> t = Tlv.primitives(dg12);
		out.documentDetails = new DocumentDetails(text(t, 0x5F19), date(t, 0x5F26), text(t, 0x5F1B));
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void parseDg13(byte[] dg13, Passport.Builder out) {
		for (final Tlv t : Tlv.primitives(dg13)) {
			String text = decodeString(t.value);
			if (looksBinary(text)) text = Bytes.hex(t.value);
			out.nationalData.put(String.format(Locale.ROOT, "tag_%02X", t.tag), text);
		}
	}

	private static String dgName(int tag) {
		switch (tag) {
			case 0x61: return "DG1";
			case 0x75: return "DG2";
			case 0x63: return "DG3";
			case 0x76: return "DG4";
			case 0x65: return "DG5";
			case 0x66: return "DG6";
			case 0x67: return "DG7";
			case 0x68: return "DG8";
			case 0x69: return "DG9";
			case 0x6A: return "DG10";
			case 0x6B: return "DG11";
			case 0x6C: return "DG12";
			case 0x6D: return "DG13";
			case 0x6E: return "DG14";
			case 0x6F: return "DG15";
			case 0x70: return "DG16";
			default: return String.format(Locale.ROOT, "0x%02X", tag);
		}
	}

	private static String text(List<Tlv> t, int tag) {
		final Tlv v = Tlv.find(t, tag);
		return (v == null || v.value.length == 0) ? null : decodeString(v.value);
	}

	private static String date(List<Tlv> t, int tag) {
		final Tlv v = Tlv.find(t, tag);
		if (v == null || v.value.length == 0) return null;
		final byte[] d = v.value;
		if (d.length == 4) {
			// BCD-packed YYYYMMDD.
			return String.format(Locale.ROOT, "%02X%02X-%02X-%02X", d[0], d[1], d[2], d[3]);
		}
		if (d.length == 8) {
			final String s = new String(d, StandardCharsets.US_ASCII);
			return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
		}
		return decodeString(d);
	}

	/** UTF-16BE when most high bytes are ≤ 5 (Cyrillic, CJK in DG11/13); otherwise UTF-8 with '<' filler blanked. */
	private static String decodeString(byte[] data) {
		if (data.length >= 4 && data.length % 2 == 0) {
			int lowHigh = 0;
			for (int i = 0; i < data.length; i += 2) {
				if ((data[i] & 0xFF) <= 5) ++lowHigh;
			}
			if (lowHigh * 100 >= (data.length / 2) * 70) {
				return new String(data, StandardCharsets.UTF_16BE).trim();
			}
		}
		return new String(data, StandardCharsets.UTF_8).trim().replace('<', ' ').trim();
	}

	private static boolean looksBinary(String s) {
		int bad = 0;
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c < ' ' && c != '\n' && c != '\t') ++bad;
			if (c == 0xFFFD) ++bad;
		}
		return bad > s.length() / 4;
	}
}
