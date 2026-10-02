/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card.iso7816;

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
}
