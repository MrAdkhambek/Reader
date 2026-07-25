/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.tech;

/**
 * Byte / hex / packed-block helpers shared by the bank-card and ID-card readers.
 * The {@link #pad}/{@link #unpad} pair implements ISO 7816-4 padding (0x80 then
 * zeros), as used by BAC and Secure Messaging.
 */
public final class Hex {
	private static final char[] HEX_CHARS = {
			'0', '1', '2', '3', '4', '5', '6', '7',
			'8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};

	private Hex() {
	}

	public static String encode(byte[] b) {
		return (b == null || b.length == 0) ? "" : encode(b, 0, b.length);
	}

	public static String encode(byte[] d, int s, int n) {
		final char[] ret = new char[n * 2];
		final int e = s + n;
		int x = 0;
		for (int i = s; i < e; ++i) {
			final byte v = d[i];
			ret[x++] = HEX_CHARS[(v >> 4) & 0xF];
			ret[x++] = HEX_CHARS[v & 0xF];
		}
		return new String(ret);
	}

	public static int toInt(byte... b) {
		int ret = 0;
		for (final byte a : b) {
			ret = (ret << 8) | (a & 0xFF);
		}
		return ret;
	}

	public static int toIntR(byte... b) {
		int ret = 0;
		for (int i = b.length - 1; i >= 0; --i) {
			ret = (ret << 8) | (b[i] & 0xFF);
		}
		return ret;
	}

	public static byte[] concat(byte[]... parts) {
		int n = 0;
		for (final byte[] p : parts) n += p.length;
		final byte[] out = new byte[n];
		int o = 0;
		for (final byte[] p : parts) {
			System.arraycopy(p, 0, out, o, p.length);
			o += p.length;
		}
		return out;
	}

	public static byte[] xor(byte[] a, byte[] b) {
		final byte[] out = new byte[a.length];
		for (int i = 0; i < a.length; ++i) out[i] = (byte) (a[i] ^ b[i]);
		return out;
	}

	public static byte[] slice(byte[] a, int from, int len) {
		final byte[] out = new byte[len];
		System.arraycopy(a, from, out, 0, len);
		return out;
	}

	/** ISO 7816-4 padding: append 0x80, then zeros to a multiple of {@code blockSize}. */
	public static byte[] pad(byte[] data, int blockSize) {
		final int padLen = blockSize - (data.length % blockSize);
		final byte[] out = new byte[data.length + padLen];
		System.arraycopy(data, 0, out, 0, data.length);
		out[data.length] = (byte) 0x80;
		return out;
	}

	/** Inverse of {@link #pad}: strip trailing zeros, then a single 0x80. */
	public static byte[] unpad(byte[] data) {
		int i = data.length - 1;
		while (i >= 0 && data[i] == 0) --i;
		if (i < 0 || data[i] != (byte) 0x80) {
			throw new IllegalArgumentException("bad padding");
		}
		final byte[] out = new byte[i];
		System.arraycopy(data, 0, out, 0, i);
		return out;
	}
}
