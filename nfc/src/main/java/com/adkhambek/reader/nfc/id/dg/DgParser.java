/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.dg;

import java.util.ArrayList;
import java.util.List;

import com.adkhambek.reader.nfc.tech.Hex;
import com.adkhambek.reader.common.mrz.IdCard;
import com.adkhambek.reader.common.mrz.Mrz;
import com.adkhambek.reader.nfc.tech.Iso7816;

/**
 * Parsers for EF.COM (DG list) and the DGs we support — DG1, DG11, DG12, DG13.
 * DG2 is handled separately in {@link Dg2}.
 */
public final class DgParser {
	private DgParser() {
	}

	/**
	 * DG1 — strip the 5F1F TLV wrapper and hand the raw MRZ string to
	 * {@link Mrz#decodeMrz}. The TLV unwrapping lives here rather than in
	 * {@code :common} so that module stays free of any APDU dependency.
	 */
	public static void parseDg1(byte[] dg1, IdCard.Builder card) {
		final Iso7816.BerTLV mrzTlv = Iso7816.BerHouse.from(dg1).findFirst(0x5F1F);
		if (mrzTlv == null) return;
		Mrz.decodeMrz(new String(mrzTlv.v.getBytes()), card);
	}

	public static List<String> parseCom(byte[] com) {
		final Iso7816.BerTLV list = Iso7816.BerHouse.from(com).findFirst((byte) 0x5C);
		if (list == null) return new ArrayList<>();
		final List<String> out = new ArrayList<>();
		for (final byte b : list.v.getBytes()) out.add(dgName(b & 0xFF));
		return out;
	}

	public static void parseDg11(byte[] dg11, IdCard.Builder card) {
		final Iso7816.BerHouse h = Iso7816.BerHouse.from(dg11);
		card.fullName(textOf(h, 0x5F0E))
				.otherNames(textOf(h, 0x5F0F))
				// 5F10 is a fallback for cards that leave the MRZ field blank —
				// it must not overwrite what DG1 already gave us.
				.personalNumberIfAbsent(textOf(h, 0x5F10))
				.placeOfBirth(textOf(h, 0x5F11))
				.fullDateOfBirth(dateOf(h, 0x5F2B))
				.address(textOf(h, 0x5F42))
				.telephone(textOf(h, 0x5F12))
				.profession(textOf(h, 0x5F13))
				.title(textOf(h, 0x5F14));
	}

	public static void parseDg12(byte[] dg12, IdCard.Builder card) {
		final Iso7816.BerHouse h = Iso7816.BerHouse.from(dg12);
		card.issuingAuthority(textOf(h, 0x5F19))
				.dateOfIssue(dateOf(h, 0x5F26))
				.endorsements(textOf(h, 0x5F1B));
	}

	public static void parseDg13(byte[] dg13, IdCard.Builder card) {
		final Iso7816.BerHouse h = Iso7816.BerHouse.from(dg13);
		for (int i = 0; i < h.count(); ++i) {
			final Iso7816.BerTLV tlv = h.get(i);
			final byte[] v = tlv.v.getBytes();
			String text = decodeString(v);
			if (looksBinary(text)) text = Hex.encode(v);
			card.putNationalData("tag_" + tlv.t.toString(), text);
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
			default: return String.format("0x%02X", tag);
		}
	}

	private static String textOf(Iso7816.BerHouse h, int tag) {
		final Iso7816.BerTLV tlv = h.findFirst(tag);
		if (tlv == null || tlv.v.size() == 0) return null;
		return decodeString(tlv.v.getBytes());
	}

	private static String dateOf(Iso7816.BerHouse h, int tag) {
		final Iso7816.BerTLV tlv = h.findFirst(tag);
		if (tlv == null || tlv.v.size() == 0) return null;
		return decodeDate(tlv.v.getBytes());
	}

	/** Heuristic: 4+ even-length byte streams with most upper bytes ≤ 5 are UTF-16BE
	 * (Cyrillic, CJK, etc. in DG11/13). Otherwise UTF-8 with '<' filler replaced. */
	public static String decodeString(byte[] data) {
		if (data.length >= 4 && data.length % 2 == 0) {
			int lowHi = 0;
			for (int i = 0; i < data.length; i += 2) {
				if ((data[i] & 0xFF) <= 5) ++lowHi;
			}
			if (lowHi * 100 >= (data.length / 2) * 70) {
				try {
					return new String(data, "UTF-16BE").trim();
				} catch (Exception ignored) {
				}
			}
		}
		try {
			return new String(data, "UTF-8").trim().replace('<', ' ').trim();
		} catch (Exception e) {
			return new String(data).trim();
		}
	}

	public static String decodeDate(byte[] data) {
		if (data.length == 4) {
			// BCD-packed YYYYMMDD.
			return String.format("%02X%02X-%02X-%02X", data[0], data[1], data[2], data[3]);
		}
		if (data.length == 8) {
			final String s = new String(data);
			try {
				return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
			} catch (Exception e) {
				return s;
			}
		}
		return decodeString(data);
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
