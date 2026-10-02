/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** EMV contactless read: PPSE → each AID → GPO → AFL records → {@link CardApp}. */
final class Emv {
	private static final byte[] PPSE = "2PAY.SYS.DDF01".getBytes(StandardCharsets.US_ASCII);

	private Emv() {
	}

	/** @return the card's payment applications, or null if the card has no PPSE */
	static List<CardApp> read(Apdu apdu) throws IOException {
		final byte[] ppse = apdu.send(select(PPSE));
		if (!Apdu.ok(ppse)) return null;
		final List<CardApp> apps = new ArrayList<>();
		for (final Tlv aid : Tlv.findAll(Tlv.primitives(Apdu.data(ppse)), 0x4F)) {
			try {
				final CardApp app = readApp(apdu, aid.value);
				if (app != null) apps.add(app);
			} catch (IllegalArgumentException malformed) {
				// A malformed FCI/GPO skips this AID, not the whole card.
			}
		}
		return apps;
	}

	private static CardApp readApp(Apdu apdu, byte[] aid) throws IOException {
		final byte[] fci = apdu.send(select(aid));
		if (!Apdu.ok(fci)) return null;
		final List<Tlv> all = Tlv.primitives(Apdu.data(fci));

		final Tlv pdol = Tlv.find(all, 0x9F38);
		final byte[] gpo = apdu.send(gpoCommand(pdol == null ? new byte[0] : pdol.value));
		if (!Apdu.ok(gpo)) return null;

		final byte[] gpoData = Apdu.data(gpo);
		final byte[] afl;
		if (gpoData.length >= 2 && gpoData[0] == (byte) 0x80) {
			afl = aflFromFormat1(gpoData);
		} else {
			final List<Tlv> gpoTlvs = Tlv.primitives(gpoData);
			all.addAll(gpoTlvs);
			final Tlv t94 = Tlv.find(gpoTlvs, 0x94);
			afl = (t94 == null) ? null : t94.value;
		}
		if (afl == null) return null;

		for (int i = 0; i + 4 <= afl.length; i += 4) {
			final int sfi = (afl[i] & 0xFF) >>> 3;
			final int last = afl[i + 2] & 0xFF;
			for (int rec = afl[i + 1] & 0xFF; rec <= last; ++rec) {
				final byte[] r = apdu.send(
						new byte[]{0x00, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 0x04), 0x00});
				if (!Apdu.ok(r)) continue;
				try {
					all.addAll(Tlv.primitives(Apdu.data(r)));
				} catch (IllegalArgumentException malformed) {
					// One bad record must not cost the whole card; the PAN usually lives in another.
				}
			}
		}

		final String pan = extractPan(all);
		if (pan == null) return null;
		return new CardApp(aid, label(all, aid), formatPan(pan), digits(all, 0x5F34),
				text(all, 0x5F20), country(all), currency(all), ymFromBcd(all, 0x5F25),
				extractExpiry(all), hexOf(all, 0x9F08));
	}

	private static byte[] select(byte[] name) {
		final byte[] cmd = new byte[name.length + 6];
		cmd[1] = (byte) 0xA4;
		cmd[2] = 0x04;
		cmd[4] = (byte) name.length;
		System.arraycopy(name, 0, cmd, 5, name.length);
		return cmd; // trailing Le = 00
	}

	private static byte[] gpoCommand(byte[] pdol) {
		final byte[] arg = gpoArgument(pdol);
		final byte[] cmd = new byte[arg.length + 6];
		cmd[0] = (byte) 0x80;
		cmd[1] = (byte) 0xA8;
		cmd[4] = (byte) arg.length;
		System.arraycopy(arg, 0, cmd, 5, arg.length);
		return cmd; // trailing Le = 00
	}

	/** GPO command data: tag 83 wrapping the fields the PDOL asks for, zero unless known. */
	static byte[] gpoArgument(byte[] pdol) {
		final ByteArrayOutputStream fields = new ByteArrayOutputStream();
		for (int i = 0; i < pdol.length; ) {
			final int tagLen = Tlv.tagLength(pdol, i);
			int tag = 0;
			for (int k = 0; k < tagLen; ++k) tag = (tag << 8) | (pdol[i++] & 0xFF);
			if (i >= pdol.length) throw new IllegalArgumentException("malformed PDOL");
			final byte[] v = new byte[pdol[i++] & 0xFF];
			if ((tag == 0x9F1A || tag == 0x5F2A) && v.length >= 2) {
				// Terminal country / transaction currency: 0840 (US / USD).
				v[0] = 0x08;
				v[1] = 0x40;
			} else if (tag == 0x9F66 && v.length >= 4) {
				// Terminal transaction qualifiers.
				v[0] = (byte) 0xF6;
				v[1] = 0x20;
				v[2] = 0x40;
				v[3] = 0x00;
			}
			fields.write(v, 0, v.length);
		}
		final byte[] data = fields.toByteArray();
		final byte[] arg = new byte[data.length + 2];
		arg[0] = (byte) 0x83;
		arg[1] = (byte) data.length;
		System.arraycopy(data, 0, arg, 2, data.length);
		return arg;
	}

	/**
	 * The AFL from a GPO Format-1 response: {@code 80 <len> <AIP 2 bytes> <AFL>}.
	 * Handles long-form lengths: {@code 80 81 nn} used to be misread as length
	 * 0x81, which dropped the AFL and rejected a valid card.
	 *
	 * @return the AFL, or null if this is not Format-1 or carries no AFL
	 */
	static byte[] aflFromFormat1(byte[] gpoData) {
		if (gpoData.length < 2 || gpoData[0] != (byte) 0x80) return null;
		int p = 1;
		int len = gpoData[p++] & 0xFF;
		if (len >= 0x80) {
			final int count = len & 0x7F;
			len = 0;
			for (int k = 0; k < count && p < gpoData.length; ++k) {
				len = (len << 8) | (gpoData[p++] & 0xFF);
			}
		}
		final int valEnd = Math.min(p + len, gpoData.length);
		final int aflStart = p + 2; // skip the 2-byte AIP
		if (valEnd - aflStart < 4) return null;
		return Arrays.copyOfRange(gpoData, aflStart, valEnd);
	}

	static String extractPan(List<Tlv> all) {
		final Tlv t57 = Tlv.find(all, 0x57);
		if (t57 != null) {
			final String hex = Bytes.hex(t57.value);
			final int sep = hex.indexOf('D');
			if (sep > 0) return hex.substring(0, sep);
		}
		final Tlv t5a = Tlv.find(all, 0x5A);
		if (t5a != null) {
			final String hex = Bytes.hex(t5a.value);
			final int f = hex.indexOf('F');
			return (f > 0) ? hex.substring(0, f) : hex;
		}
		return null;
	}

	static String extractExpiry(List<Tlv> all) {
		final Tlv t57 = Tlv.find(all, 0x57);
		if (t57 != null) {
			final String hex = Bytes.hex(t57.value);
			final int sep = hex.indexOf('D');
			if (sep > 0 && hex.length() >= sep + 5) {
				return "20" + hex.substring(sep + 1, sep + 3) + "." + hex.substring(sep + 3, sep + 5);
			}
		}
		return ymFromBcd(all, 0x5F24);
	}

	static String ymFromBcd(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		if (t == null || t.value.length < 2) return null;
		return String.format(Locale.ROOT, "20%02X.%02X", t.value[0] & 0xFF, t.value[1] & 0xFF);
	}

	static String country(List<Tlv> all) {
		final Tlv t = Tlv.find(all, 0x5F28);
		if (t == null || t.value.length < 2) return null;
		return String.format(Locale.ROOT, "%02X%02X", t.value[0] & 0xFF, t.value[1] & 0xFF);
	}

	private static String text(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		return (t == null) ? null : new String(t.value, StandardCharsets.UTF_8).trim();
	}

	private static String hexOf(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		return (t == null) ? null : Bytes.hex(t.value);
	}

	/** BCD digits, skipping any nibble above 9 (filler). */
	private static String digits(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		if (t == null) return null;
		final StringBuilder sb = new StringBuilder(t.value.length * 2);
		for (final byte v : t.value) {
			final int hi = (v >> 4) & 0xF;
			final int lo = v & 0xF;
			if (hi <= 9) sb.append((char) ('0' + hi));
			if (lo <= 9) sb.append((char) ('0' + lo));
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String label(List<Tlv> all, byte[] aid) {
		final String label = text(all, 0x50);
		if (label != null && !label.isEmpty()) return label;
		final String preferred = text(all, 0x9F12);
		if (preferred != null && !preferred.isEmpty()) return preferred;
		return identify(aid);
	}

	private static String formatPan(String digits) {
		final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 4);
		for (int i = 0; i < digits.length(); ++i) {
			if (i > 0 && i % 4 == 0) out.append(' ');
			out.append(digits.charAt(i));
		}
		return out.toString();
	}

	static Currency currency(List<Tlv> all) {
		Tlv t = Tlv.find(all, 0x9F42);
		if (t == null) t = Tlv.find(all, 0x5F2A);
		if (t == null || t.value.length < 2) return null;
		switch (((t.value[0] & 0xFF) << 8) | (t.value[1] & 0xFF)) {
			case 0x0840: return Currency.USD;
			case 0x0978: return Currency.EUR;
			case 0x0826: return Currency.GBP;
			case 0x0392: return Currency.JPY;
			case 0x0756: return Currency.CHF;
			case 0x0124: return Currency.CAD;
			case 0x0036: return Currency.AUD;
			case 0x0554: return Currency.NZD;
			case 0x0156: return Currency.CNY;
			case 0x0344: return Currency.HKD;
			case 0x0901: return Currency.TWD;
			case 0x0410: return Currency.KRW;
			case 0x0702: return Currency.SGD;
			case 0x0356: return Currency.INR;
			case 0x0360: return Currency.IDR;
			case 0x0764: return Currency.THB;
			case 0x0458: return Currency.MYR;
			case 0x0608: return Currency.PHP;
			case 0x0704: return Currency.VND;
			case 0x0643: return Currency.RUB;
			case 0x0949: return Currency.TRY;
			case 0x0784: return Currency.AED;
			case 0x0682: return Currency.SAR;
			case 0x0376: return Currency.ILS;
			case 0x0710: return Currency.ZAR;
			case 0x0986: return Currency.BRL;
			case 0x0484: return Currency.MXN;
			case 0x0860: return Currency.UZS;
			case 0x0398: return Currency.KZT;
			case 0x0417: return Currency.KGS;
			case 0x0944: return Currency.AZN;
			case 0x0981: return Currency.GEL;
			case 0x0051: return Currency.AMD;
			case 0x0933: return Currency.BYN;
			case 0x0980: return Currency.UAH;
			default: return null;
		}
	}

	static String identify(byte[] aid) {
		final String h = Bytes.hex(aid);
		if (h.startsWith("A0000000031010")) return "Visa";
		if (h.startsWith("A0000000041010")) return "Mastercard";
		if (h.startsWith("A000000333")) return "UnionPay";
		if (h.startsWith("A0000000651010")) return "JCB";
		if (h.startsWith("A0000000250000") || h.startsWith("A000000025010402")) return "American Express";
		if (h.startsWith("A0000001523010") || h.startsWith("A0000001524010")) return "Discover";
		if (h.startsWith("A0000000043060")) return "Maestro";
		if (h.startsWith("A0000005241010")) return "RuPay";
		if (h.startsWith("A0000006581010") || h.startsWith("A0000006582010")) return "HUMO";
		if (h.startsWith("A0000005942010")) return "UZCARD";
		return "Unknown";
	}
}
