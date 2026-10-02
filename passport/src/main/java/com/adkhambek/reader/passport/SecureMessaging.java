/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * ICAO 9303 Secure Messaging over 3DES + retail MAC.
 *
 * <p>Command: CLA |= 0x0C; DO87 = 01 ‖ encrypted padded data; DO97 = Le;
 * DO8E = MAC(SSC ‖ padded header ‖ DO87 ‖ DO97). Response: DO87, DO99 (the real
 * status word), DO8E. The SSC is incremented before each MAC, on both sides.
 */
final class SecureMessaging {
	private final byte[] ksEnc;
	private final byte[] ksMac;
	private final byte[] ssc;

	SecureMessaging(Bac bac) {
		this(bac.ksEnc, bac.ksMac, bac.ssc);
	}

	SecureMessaging(byte[] ksEnc, byte[] ksMac, byte[] ssc) {
		this.ksEnc = ksEnc;
		this.ksMac = ksMac;
		this.ssc = ssc.clone();
	}

	/** Wrap {@code plain}, send it, return the unwrapped {@code data ‖ SW}. */
	byte[] transceive(Apdu apdu, byte[] plain) throws IOException {
		final byte[] r = apdu.send(wrap(plain));
		return unwrap(Apdu.data(r), Apdu.sw(r));
	}

	byte[] wrap(byte[] plain) {
		final Command a = Command.parse(plain);
		final byte cla = (byte) (a.cla | 0x0C);
		final byte[] paddedHeader = Bytes.pad(new byte[]{cla, a.ins, a.p1, a.p2});

		byte[] do87 = new byte[0];
		if (a.data.length > 0) {
			do87 = tlv(0x87, Bytes.concat(new byte[]{0x01}, Bac.des3(ksEnc, Bytes.pad(a.data), true)));
		}
		byte[] do97 = new byte[0];
		if (a.le >= 0) {
			// Short: 256 encodes as 00. Extended: 65536 encodes as 0000.
			do97 = tlv(0x97, a.extended
					? new byte[]{(byte) (a.le >> 8), (byte) a.le}
					: new byte[]{(byte) a.le});
		}

		increment(ssc);
		final byte[] mac = Iso9797Mac.mac(ksMac, Bytes.concat(ssc, paddedHeader, do87, do97));
		final byte[] body = Bytes.concat(do87, do97, tlv(0x8E, mac));

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(cla);
		out.write(a.ins);
		out.write(a.p1);
		out.write(a.p2);
		if (a.extended || body.length > 0xFF) {
			out.write(0x00);
			out.write(body.length >> 8);
			out.write(body.length);
			out.write(body, 0, body.length);
			out.write(0x00);
			out.write(0x00);
		} else {
			out.write(body.length);
			out.write(body, 0, body.length);
			out.write(0x00);
		}
		return out.toByteArray();
	}

	byte[] unwrap(byte[] body, int outerSw) {
		// A chip may answer an error with a bare status word and no SM objects —
		// 6A82 for a file that does not exist. There is nothing to verify; pass it on.
		if (body.length == 0 && outerSw != Apdu.SW_OK) {
			return new byte[]{(byte) (outerSw >> 8), (byte) outerSw};
		}

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
			final byte[] val = Arrays.copyOfRange(body, pos, pos + len);
			pos += len;
			switch (tag) {
				case 0x87: do87 = val; break;
				case 0x99: do99 = val; break;
				case 0x8E: do8e = val; break;
				default: // not part of the BAC SM profile; ignored
			}
		}

		increment(ssc);
		final byte[] expected = Iso9797Mac.mac(ksMac, Bytes.concat(ssc,
				do87 == null ? new byte[0] : tlv(0x87, do87),
				do99 == null ? new byte[0] : tlv(0x99, do99)));
		if (do8e == null || !MessageDigest.isEqual(expected, do8e)) {
			throw new IllegalStateException("SM MAC verification failed");
		}

		byte[] data = new byte[0];
		if (do87 != null) {
			// Drop the padding-content indicator (01), decrypt, unpad.
			data = Bytes.unpad(Bac.des3(ksEnc, Arrays.copyOfRange(do87, 1, do87.length), false));
		}
		final int sw = (do99 != null && do99.length == 2)
				? ((do99[0] & 0xFF) << 8) | (do99[1] & 0xFF)
				: outerSw;
		return Bytes.concat(data, new byte[]{(byte) (sw >> 8), (byte) sw});
	}

	/** One-byte tag, BER length (short, 81 or 82 form), value. */
	static byte[] tlv(int tag, byte[] value) {
		final int n = value.length;
		final byte[] len = (n < 0x80) ? new byte[]{(byte) n}
				: (n <= 0xFF) ? new byte[]{(byte) 0x81, (byte) n}
				: new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
		return Bytes.concat(new byte[]{(byte) tag}, len, value);
	}

	private static void increment(byte[] counter) {
		for (int i = counter.length - 1; i >= 0; --i) {
			if (++counter[i] != 0) return;
		}
	}

	/** A plain command APDU, ISO 7816-4 cases 1, 2S, 3S, 4S, 2E, 3E, 4E. {@code le} is -1 when absent. */
	private static final class Command {
		byte cla;
		byte ins;
		byte p1;
		byte p2;
		byte[] data = new byte[0];
		int le = -1;
		boolean extended;

		static Command parse(byte[] b) {
			final Command a = new Command();
			a.cla = b[0];
			a.ins = b[1];
			a.p1 = b[2];
			a.p2 = b[3];
			final int n = b.length;
			if (n == 4) return a;                                     // case 1
			if (n == 5) {                                             // case 2S
				a.le = orMax(b[4] & 0xFF, 256);
				return a;
			}
			if (b[4] != 0) {                                          // cases 3S, 4S
				final int lc = b[4] & 0xFF;
				a.data = Arrays.copyOfRange(b, 5, 5 + lc);
				if (n == 6 + lc) a.le = orMax(b[5 + lc] & 0xFF, 256);
				return a;
			}
			a.extended = true;
			if (n == 7) {                                             // case 2E
				a.le = orMax(u16(b, 5), 65536);
				return a;
			}
			final int lc = u16(b, 5);                                 // cases 3E, 4E
			a.data = Arrays.copyOfRange(b, 7, 7 + lc);
			if (n == 9 + lc) a.le = orMax(u16(b, 7 + lc), 65536);
			return a;
		}

		private static int u16(byte[] b, int i) {
			return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
		}

		private static int orMax(int v, int max) {
			return v == 0 ? max : v;
		}
	}
}
