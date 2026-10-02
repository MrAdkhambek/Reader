/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.iso7816;

import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal BER-TLV reader. Tags are packed big-endian into an int, so
 * {@code 0x4F}, {@code 0x9F38} and {@code 0x5F2A} compare directly.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Tlv {
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public final int tag;
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public final byte[] value;

	Tlv(int tag, byte[] value) {
		this.tag = tag;
		this.value = value;
	}

	/** Every primitive object in {@code data}, descending into constructed ones. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static List<Tlv> primitives(byte[] data) {
		return parse(data, true);
	}

	/**
	 * The objects in {@code data}. With {@code descend}, a constructed object is
	 * replaced by its contents, recursively; without, it is returned whole.
	 * {@code 00} and {@code FF} filler between objects is skipped.
	 *
	 * @throws IllegalArgumentException on truncated or malformed input
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static List<Tlv> parse(byte[] data, boolean descend) {
		final List<Tlv> out = new ArrayList<>();
		parse(data, descend, out);
		return out;
	}

	private static void parse(byte[] b, boolean descend, List<Tlv> out) {
		int p = 0;
		while (p < b.length) {
			if (b[p] == 0x00 || b[p] == (byte) 0xFF) {
				++p;
				continue;
			}
			final boolean constructed = (b[p] & 0x20) != 0;
			final int tagLen = tagLength(b, p);
			int tag = 0;
			for (int i = 0; i < tagLen; ++i) tag = (tag << 8) | (b[p++] & 0xFF);

			if (p >= b.length) throw new IllegalArgumentException("truncated length");
			int len = b[p++] & 0xFF;
			if (len >= 0x80) {
				final int n = len & 0x7F;
				if (n == 0 || n > 3 || p + n > b.length) {
					throw new IllegalArgumentException("bad length");
				}
				len = 0;
				for (int i = 0; i < n; ++i) len = (len << 8) | (b[p++] & 0xFF);
			}
			if (p + len > b.length) throw new IllegalArgumentException("truncated value");

			final byte[] value = Arrays.copyOfRange(b, p, p + len);
			p += len;
			if (descend && constructed) parse(value, true, out);
			else out.add(new Tlv(tag, value));
		}
	}

	/** Bytes taken by the tag that starts at {@code p}. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static int tagLength(byte[] b, int p) {
		if ((b[p] & 0x1F) != 0x1F) return 1;
		int n = 1;
		while (p + n < b.length) {
			if ((b[p + n++] & 0x80) == 0) return n;
		}
		throw new IllegalArgumentException("truncated tag");
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static Tlv find(List<Tlv> tlvs, int tag) {
		for (final Tlv t : tlvs) if (t.tag == tag) return t;
		return null;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static List<Tlv> findAll(List<Tlv> tlvs, int tag) {
		final List<Tlv> out = new ArrayList<>();
		for (final Tlv t : tlvs) if (t.tag == tag) out.add(t);
		return out;
	}
}
