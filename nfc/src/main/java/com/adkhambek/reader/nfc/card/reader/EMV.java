/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.card.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.adkhambek.reader.nfc.card.Currency;
import com.adkhambek.reader.nfc.card.bean.CardApp;
import com.adkhambek.reader.iso7816.Hex;
import com.adkhambek.reader.iso7816.Iso7816;
import com.adkhambek.reader.iso7816.Iso7816.BerHouse;
import com.adkhambek.reader.iso7816.Iso7816.BerT;
import com.adkhambek.reader.iso7816.Iso7816.BerTLV;
import com.adkhambek.reader.iso7816.IsoDepChannel;

public final class EMV {
	private static final String TAG = "NFCard";

	private static final byte[] PPSE = {'2', 'P', 'A', 'Y', '.', 'S', 'Y', 'S', '.', 'D', 'D', 'F', '0', '1'};

	public static List<CardApp> readCard(IsoDep tech) throws IOException {
		final List<CardApp> apps = new ArrayList<>();
		final Iso7816.StdTag tag = new Iso7816.StdTag(new IsoDepChannel(tech));
		final boolean alreadyConnected = tech.isConnected();
		tag.connect();

		try {
			Log.i(TAG, "EMV: SELECT PPSE");
			final Iso7816.Response ppse = selectByName(tag, PPSE);
			if (!ppse.isOkey()) {
				Log.i(TAG, "EMV: PPSE absent (sw=" + ppse.getSw12String() + ")");
				return apps;
			}

			final BerHouse ppseTlvs = new BerHouse();
			BerTLV.extractPrimitives(ppseTlvs, ppse);

			for (final BerTLV t : ppseTlvs.findAll((byte) 0x4F)) {
				final byte[] aid = t.v.getBytes();
				final CardApp app = tryAid(tag, aid);
				if (app != null) apps.add(app);
			}
		} finally {
			if (!alreadyConnected) tag.close();
		}
		return apps;
	}

	private static CardApp tryAid(Iso7816.StdTag tag, byte[] aid) throws IOException {
		final Iso7816.Response fci = selectByName(tag, aid);
		if (!fci.isOkey()) {
			Log.w(TAG, "EMV: AID select sw=" + fci.getSw12String());
			return null;
		}

		final BerHouse fciTlvs = new BerHouse();
		BerTLV.extractPrimitives(fciTlvs, fci);

		final BerTLV pdolTlv = fciTlvs.findFirst((short) 0x9F38);
		final byte[] pdol = (pdolTlv != null) ? pdolTlv.v.getBytes() : new byte[0];

		final byte[] gpoArg = buildGpoArg(pdol);

		final Iso7816.Response gpo = getProcessingOptions(tag, gpoArg);
		if (!gpo.isOkey()) {
			Log.w(TAG, "EMV: GPO sw=" + gpo.getSw12String());
			return null;
		}

		final BerHouse all = new BerHouse();
		copyAll(fciTlvs, all);

		final byte[] gpoData = gpo.getBytes();
		byte[] afl = null;
		if (gpoData.length >= 2 && gpoData[0] == (byte) 0x80) {
			afl = aflFromFormat1(gpoData);
		} else {
			final BerHouse gpoTlvs = new BerHouse();
			BerTLV.extractPrimitives(gpoTlvs, gpo);
			copyAll(gpoTlvs, all);
			final BerTLV t94 = gpoTlvs.findFirst((byte) 0x94);
			if (t94 != null) afl = t94.v.getBytes();
		}
		if (afl == null) {
			Log.w(TAG, "EMV: AFL missing in GPO response");
			return null;
		}

		for (int i = 0; i + 4 <= afl.length; i += 4) {
			final int sfi = (afl[i] & 0xFF) >>> 3;
			final int firstRec = afl[i + 1] & 0xFF;
			final int lastRec = afl[i + 2] & 0xFF;
			for (int rec = firstRec; rec <= lastRec; ++rec) {
				final Iso7816.Response r = readRecord(tag, sfi, rec);
				if (!r.isOkey()) {
					Log.d(TAG, "EMV: READ sfi=" + sfi + " rec=" + rec + " sw=" + r.getSw12String());
					continue;
				}
				BerTLV.extractPrimitives(all, r);
			}
		}

		final String pan = extractPan(all);
		if (pan == null) {
			Log.w(TAG, "EMV: no PAN found");
			return null;
		}

		final String label = labelOrFallback(all, aid);
		final String panSeq = hex2Digits(all, (short) 0x5F34);
		final String holder = string(all, (short) 0x5F20);
		final String ctry = country(all);
		final Currency cur = currency(all);
		final String expiry = extractExpiry(all);
		final String effective = ymFromBcd(all, (short) 0x5F25);
		final String appVer = hexBytes(all, (short) 0x9F08);

		Log.i(TAG, "EMV: card application read");

		return new CardApp(aid, label, formatPan(pan), panSeq, holder, ctry,
				cur, effective, expiry, appVer);
	}

	private static byte[] buildGpoArg(byte[] pdol) {
		int dataLen = 0;
		for (int i = 0; i < pdol.length; ) {
			final int tagLen = BerT.test(pdol, i);
			i += tagLen;
			if (i >= pdol.length) throw new IllegalArgumentException("Malformed PDOL");
			dataLen += pdol[i] & 0xFF;
			i += 1;
		}
		final byte[] out = new byte[2 + dataLen];
		out[0] = (byte) 0x83;
		out[1] = (byte) dataLen;
		fillPdolDefaults(pdol, out, 2);
		return out;
	}

	private static void fillPdolDefaults(byte[] pdol, byte[] out, int outOff) {
		int o = outOff;
		for (int i = 0; i < pdol.length; ) {
			final int tagLen = BerT.test(pdol, i);
			final byte[] tag = Arrays.copyOfRange(pdol, i, i + tagLen);
			i += tagLen;
			if (i >= pdol.length) throw new IllegalArgumentException("Malformed PDOL");
			final int valLen = pdol[i] & 0xFF;
			i += 1;

			if (tagLen == 2 && tag[0] == (byte) 0x9F && tag[1] == 0x1A && valLen >= 2) {
				out[o] = 0x08;
				out[o + 1] = 0x40;
			} else if (tagLen == 2 && tag[0] == 0x5F && tag[1] == 0x2A && valLen >= 2) {
				out[o] = 0x08;
				out[o + 1] = 0x40;
			} else if (tagLen == 2 && tag[0] == (byte) 0x9F && tag[1] == 0x66 && valLen >= 4) {
				out[o] = (byte) 0xF6;
				out[o + 1] = (byte) 0x20;
				out[o + 2] = (byte) 0x40;
				out[o + 3] = 0x00;
			} else if (tagLen == 1 && tag[0] == (byte) 0x9C && valLen >= 1) {
				out[o] = 0x00;
			}
			o += valLen;
		}
	}

	private static void copyAll(BerHouse src, BerHouse dst) {
		for (int i = 0; i < src.count(); ++i) dst.add(src.get(i));
	}

	/**
	 * Extract the AFL from a GPO Format-1 ('80' template) response:
	 * {@code 80 <len> <AIP 2 bytes> <AFL>}. Handles both short-form and long-form
	 * BER lengths — a long-form length such as {@code 80 81 nn} was previously
	 * misread as length 0x81, which dropped the AFL and rejected a valid card.
	 *
	 * @return the AFL bytes, or null if this isn't Format-1 or carries no AFL
	 */
	static byte[] aflFromFormat1(byte[] gpoData) {
		if (gpoData.length < 2 || gpoData[0] != (byte) 0x80) return null;
		int p = 1;
		int len = gpoData[p++] & 0xFF;
		if (len >= 0x80) { // long-form BER length: low 7 bits = number of length bytes
			final int count = len & 0x7F;
			len = 0;
			for (int k = 0; k < count && p < gpoData.length; ++k) {
				len = (len << 8) | (gpoData[p++] & 0xFF);
			}
		}
		final int valEnd = Math.min(p + len, gpoData.length);
		final int aflStart = p + 2; // skip the 2-byte AIP that precedes the AFL
		if (valEnd - aflStart < 4) return null;
		return Arrays.copyOfRange(gpoData, aflStart, valEnd);
	}

	static String extractPan(BerHouse all) {
		final BerTLV t57 = all.findFirst((byte) 0x57);
		if (t57 != null) {
			final String hex = Hex.encode(t57.v.getBytes());
			final int sep = indexOfAny(hex, 'D', 'd');
			if (sep > 0) return hex.substring(0, sep);
		}
		final BerTLV t5a = all.findFirst((byte) 0x5A);
		if (t5a != null) {
			final String hex = Hex.encode(t5a.v.getBytes());
			final int f = indexOfAny(hex, 'F', 'f');
			return (f > 0) ? hex.substring(0, f) : hex;
		}
		return null;
	}

	static String extractExpiry(BerHouse all) {
		final BerTLV t57 = all.findFirst((byte) 0x57);
		if (t57 != null) {
			final String hex = Hex.encode(t57.v.getBytes());
			final int sep = indexOfAny(hex, 'D', 'd');
			if (sep > 0 && hex.length() >= sep + 5) {
				final String yymm = hex.substring(sep + 1, sep + 5);
				return "20" + yymm.substring(0, 2) + "." + yymm.substring(2, 4);
			}
		}
		return ymFromBcd(all, (short) 0x5F24);
	}

	static String ymFromBcd(BerHouse all, short tag) {
		final BerTLV t = all.findFirst(tag);
		if (t == null || t.v.size() < 2) return null;
		final byte[] b = t.v.getBytes();
		return String.format("20%02X.%02X", b[0] & 0xFF, b[1] & 0xFF);
	}

	private static String string(BerHouse all, byte tag) {
		final BerTLV t = all.findFirst(tag);
		return (t == null) ? null : new String(t.v.getBytes()).trim();
	}

	private static String string(BerHouse all, short tag) {
		final BerTLV t = all.findFirst(tag);
		return (t == null) ? null : new String(t.v.getBytes()).trim();
	}

	private static String hexBytes(BerHouse all, short tag) {
		final BerTLV t = all.findFirst(tag);
		return (t == null) ? null : Hex.encode(t.v.getBytes());
	}

	private static String hex2Digits(BerHouse all, short tag) {
		final BerTLV t = all.findFirst(tag);
		if (t == null) return null;
		final byte[] b = t.v.getBytes();
		final StringBuilder sb = new StringBuilder(b.length * 2);
		for (final byte v : b) {
			final int hi = (v >> 4) & 0xF;
			final int lo = v & 0xF;
			if (hi <= 9) sb.append((char) ('0' + hi));
			if (lo <= 9) sb.append((char) ('0' + lo));
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	static Currency currency(BerHouse all) {
		BerTLV t = all.findFirst((short) 0x9F42);
		if (t == null) t = all.findFirst((short) 0x5F2A);
		if (t == null || t.v.size() < 2) return null;
		final byte[] b = t.v.getBytes();
		final int code = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
		switch (code) {
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

	static String country(BerHouse all) {
		final BerTLV t = all.findFirst((short) 0x5F28);
		if (t == null || t.v.size() < 2) return null;
		final byte[] b = t.v.getBytes();
		return String.format("%02X%02X", b[0] & 0xFF, b[1] & 0xFF);
	}

	private static String labelOrFallback(BerHouse all, byte[] aid) {
		final String label = string(all, (byte) 0x50);
		if (label != null && !label.isEmpty()) return label;
		final String label2 = string(all, (short) 0x9F12);
		if (label2 != null && !label2.isEmpty()) return label2;
		return identify(aid);
	}

	private static int indexOfAny(String s, char a, char b) {
		final int ia = s.indexOf(a);
		final int ib = s.indexOf(b);
		if (ia < 0) return ib;
		if (ib < 0) return ia;
		return Math.min(ia, ib);
	}

	private static String formatPan(String digits) {
		final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 4);
		for (int i = 0; i < digits.length(); ++i) {
			if (i > 0 && i % 4 == 0) out.append(' ');
			out.append(digits.charAt(i));
		}
		return out.toString();
	}

	private static Iso7816.Response selectByName(Iso7816.StdTag tag, byte[] name) throws IOException {
		final ByteBuffer b = ByteBuffer.allocate(name.length + 6);
		b.put((byte) 0x00).put((byte) 0xA4).put((byte) 0x04).put((byte) 0x00)
				.put((byte) name.length).put(name).put((byte) 0x00);
		return tag.transceive(b.array());
	}

	private static Iso7816.Response getProcessingOptions(Iso7816.StdTag tag, byte[] pdol) throws IOException {
		final ByteBuffer b = ByteBuffer.allocate(pdol.length + 6);
		b.put((byte) 0x80).put((byte) 0xA8).put((byte) 0x00).put((byte) 0x00)
				.put((byte) pdol.length).put(pdol).put((byte) 0x00);
		return tag.transceive(b.array());
	}

	private static Iso7816.Response readRecord(Iso7816.StdTag tag, int sfi, int index) throws IOException {
		final byte[] cmd = {0x00, (byte) 0xB2, (byte) index, (byte) ((sfi << 3) | 0x04), 0x00};
		return tag.transceive(cmd);
	}

	static String identify(byte[] aid) {
		final String h = Hex.encode(aid);
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
