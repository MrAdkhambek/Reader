/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.iso7816;

import androidx.annotation.RestrictTo;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Bytes {
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private Bytes() {
	}

	/** Upper-case hex, two characters per byte. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static String hex(byte[] b) {
		final char[] out = new char[b.length * 2];
		for (int i = 0; i < b.length; ++i) {
			out[2 * i] = HEX[(b[i] >> 4) & 0xF];
			out[2 * i + 1] = HEX[b[i] & 0xF];
		}
		return new String(out);
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
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

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static byte[] xor(byte[] a, byte[] b) {
		final byte[] out = new byte[a.length];
		for (int i = 0; i < a.length; ++i) out[i] = (byte) (a[i] ^ b[i]);
		return out;
	}

	/** ISO 7816-4 padding to 8-byte blocks: 0x80, then zeros. Always adds at least one byte. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static byte[] pad(byte[] data) {
		final byte[] out = new byte[(data.length / 8 + 1) * 8];
		System.arraycopy(data, 0, out, 0, data.length);
		out[data.length] = (byte) 0x80;
		return out;
	}

	/** Inverse of {@link #pad}: strip trailing zeros, then one 0x80. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static byte[] unpad(byte[] data) {
		int i = data.length - 1;
		while (i >= 0 && data[i] == 0) --i;
		if (i < 0 || data[i] != (byte) 0x80) throw new IllegalArgumentException("bad padding");
		final byte[] out = new byte[i];
		System.arraycopy(data, 0, out, 0, i);
		return out;
	}
}
