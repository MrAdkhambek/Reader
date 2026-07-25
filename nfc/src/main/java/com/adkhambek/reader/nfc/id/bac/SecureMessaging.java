/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.bac;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import com.adkhambek.reader.common.Hex;
import com.adkhambek.reader.common.tech.Iso7816;
import com.adkhambek.reader.common.tech.Iso7816.BerTLV;

/**
 * ICAO 9303 Secure Messaging over 3DES/MAC. Wraps and unwraps plain APDUs
 * around the BAC session keys.
 *
 * <p>Each plain APDU becomes a wrapped APDU with:
 * <ul>
 *   <li>CLA |= 0x0C (secure messaging bit)</li>
 *   <li>DO87 (tag 0x87) — encrypted command data, prefixed with the
 *       padding-content indicator byte 0x01</li>
 *   <li>DO97 (tag 0x97) — expected Le (if any)</li>
 *   <li>DO8E (tag 0x8E) — MAC of SSC ∥ paddedHeader ∥ DO87 ∥ DO97</li>
 * </ul>
 * Response: DO87 (encrypted), DO99 (the real SW1 SW2), DO8E (MAC).
 *
 * <p>SSC is incremented once before each MAC computation (wrap and unwrap).
 */
public final class SecureMessaging {
	private final byte[] ksEnc;
	private final byte[] ksMac;
	private final byte[] ssc;

	public SecureMessaging(Bac bac) {
		this.ksEnc = bac.ksEnc;
		this.ksMac = bac.ksMac;
		this.ssc = bac.ssc.clone();
	}

	public Iso7816.Response transceive(Iso7816.StdTag tag, byte[] plainApdu) throws Exception {
		final byte[] wrapped = wrap(plainApdu);
		final Iso7816.Response wrappedResp = tag.transceive(wrapped);
		return new Iso7816.Response(unwrap(wrappedResp.getPayload(), wrappedResp.getSw12()));
	}

	byte[] wrap(byte[] plain) throws Exception {
		final byte cla = (byte) (plain[0] | 0x0C);
		final byte ins = plain[1];
		final byte p1 = plain[2];
		final byte p2 = plain[3];

		byte[] data = null;
		Integer le = null;
		if (plain.length == 5) {
			le = plain[4] & 0xFF;
		} else if (plain.length > 5) {
			final int lc = plain[4] & 0xFF;
			data = Hex.slice(plain, 5, lc);
			if (plain.length > 5 + lc) le = plain[5 + lc] & 0xFF;
		}

		final byte[] paddedHeader = Hex.pad(new byte[]{cla, ins, p1, p2}, 8);
		byte[] do87 = new byte[0];
		if (data != null) {
			final byte[] padded = Hex.pad(data, 8);
			final byte[] enc = Bac.des3(ksEnc, padded, true, new byte[8]);
			do87 = BerTLV.encode(0x87, Hex.concat(new byte[]{0x01}, enc));
		}
		byte[] do97 = new byte[0];
		if (le != null) {
			do97 = BerTLV.encode(0x97, new byte[]{(byte) le.intValue()});
		}

		increment(ssc);
		final byte[] mac = Iso9797Mac.mac(ksMac, Hex.concat(ssc, paddedHeader, do87, do97));
		final byte[] do8e = BerTLV.encode(0x8E, mac);

		final byte[] body = Hex.concat(do87, do97, do8e);
		if (body.length > 0xFF) {
			// Lc below is a single short-form byte; out.write(int) would silently
			// truncate it to body.length & 0xFF. A >255-byte protected body needs
			// an extended-length APDU. BAC only wraps small SELECT/READ BINARY
			// commands, so this is unreachable today — fail loudly if that changes.
			throw new IllegalStateException("SM body too large for short-form Lc: " + body.length);
		}
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(cla);
		out.write(ins);
		out.write(p1);
		out.write(p2);
		out.write(body.length);
		out.write(body, 0, body.length);
		out.write(0x00);
		return out.toByteArray();
	}

	byte[] unwrap(byte[] body, short outerSw) throws Exception {
		byte[] do87 = null;
		byte[] do99 = null;
		byte[] do8e = null;

		int pos = 0;
		while (pos < body.length) {
			final int tag = body[pos++] & 0xFF;
			int len = body[pos++] & 0xFF;
			if (len >= 0x80) {
				final int n = len & 0x7F;
				len = 0;
				for (int i = 0; i < n; ++i) len = (len << 8) | (body[pos++] & 0xFF);
			}
			final byte[] val = Hex.slice(body, pos, len);
			pos += len;
			switch (tag) {
				case 0x87: do87 = val; break;
				case 0x99: do99 = val; break;
				case 0x8E: do8e = val; break;
			}
		}

		increment(ssc);
		final ByteArrayOutputStream macIn = new ByteArrayOutputStream();
		macIn.write(ssc, 0, ssc.length);
		if (do87 != null) {
			final byte[] tlv = BerTLV.encode(0x87, do87);
			macIn.write(tlv, 0, tlv.length);
		}
		if (do99 != null) {
			final byte[] tlv = BerTLV.encode(0x99, do99);
			macIn.write(tlv, 0, tlv.length);
		}
		final byte[] expected = Iso9797Mac.mac(ksMac, macIn.toByteArray());
		if (do8e == null || !Arrays.equals(expected, do8e)) {
			throw new RuntimeException("SM MAC verification failed");
		}

		byte[] data = new byte[0];
		if (do87 != null) {
			// Strip the leading padding-content indicator byte (0x01), then decrypt + unpad.
			final byte[] enc = Hex.slice(do87, 1, do87.length - 1);
			final byte[] padded = Bac.des3(ksEnc, enc, false, new byte[8]);
			data = Hex.unpad(padded);
		}

		short sw = outerSw;
		if (do99 != null && do99.length == 2) {
			sw = (short) (((do99[0] & 0xFF) << 8) | (do99[1] & 0xFF));
		}
		return Hex.concat(data, new byte[]{(byte) ((sw >> 8) & 0xFF), (byte) (sw & 0xFF)});
	}

	private static void increment(byte[] ssc) {
		for (int i = ssc.length - 1; i >= 0; --i) {
			ssc[i] = (byte) (ssc[i] + 1);
			if (ssc[i] != 0) return;
		}
	}
}
