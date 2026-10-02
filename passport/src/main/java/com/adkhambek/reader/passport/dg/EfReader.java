/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.dg;

import androidx.annotation.RestrictTo;

import com.adkhambek.reader.passport.iso7816.Apdu;
import com.adkhambek.reader.passport.iso7816.Tlv;
import com.adkhambek.reader.passport.iso7816.Transceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Reads one elementary file: SELECT by FID, then READ BINARY in chunks. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class EfReader {
	/** 223 data bytes plus SM overhead fit a 256-byte short response. */
	static final int SHORT_CHUNK = 0xDF;
	/** Tag + up to 0x83 + 3 length bytes: enough to size any file up to 16 MB. */
	static final int HEAD_LEN = 5;
	/** Response bytes that are not file data: DO87 header 5 + padding ≤ 8 + DO99 4 + DO8E 10 + SW 2. */
	static final int SM_OVERHEAD = 29;

	private final Transceiver sm;
	private final int chunk;
	private final boolean extended;

	/**
	 * @param sm                plain APDU in, unwrapped {@code data ‖ SW} out
	 * @param maxResponseLength largest response both phone and chip accept; 0 = short form only
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public EfReader(Transceiver sm, int maxResponseLength) {
		this.sm = sm;
		final int extendedChunk = Math.min(maxResponseLength, 65536) - SM_OVERHEAD;
		// Go extended only when it buys more than a short Le can express.
		this.extended = extendedChunk > 0xFF;
		this.chunk = extended ? extendedChunk : SHORT_CHUNK;
	}

	/** @return the whole file, or null if the chip says it does not exist (6A82) */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public byte[] read(int fid) throws IOException {
		final byte[] sel = sm.transceive(
				new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, (byte) (fid >> 8), (byte) fid});
		final int sw = Apdu.sw(sel);
		if (sw == 0x6A82) return null;
		if (sw != Apdu.SW_OK) {
			throw new IllegalStateException(String.format(Locale.ROOT, "SELECT %04X SW=%04X", fid, sw));
		}

		final byte[] head = readBinary(0, HEAD_LEN, false);
		final int total = totalLength(head);
		final ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.write(head, 0, head.length);
		int off = head.length;
		while (off < total) {
			final byte[] part = readBinary(off, Math.min(chunk, total - off), extended);
			// A 9000 with no bytes (EOF quirk) would otherwise spin forever.
			if (part.length == 0) break;
			all.write(part, 0, part.length);
			off += part.length;
		}
		return all.toByteArray();
	}

	private byte[] readBinary(int offset, int len, boolean ext) throws IOException {
		// B0 with P1 bit 8 clear addresses the current EF with a 15-bit offset.
		if (offset > 0x7FFF) {
			throw new IllegalStateException("files over 32 KB are not supported (offset " + offset + ")");
		}
		final byte p1 = (byte) (offset >> 8);
		final byte p2 = (byte) offset;
		final byte[] r = sm.transceive(ext
				? new byte[]{0x00, (byte) 0xB0, p1, p2, 0x00, (byte) (len >> 8), (byte) len} // case 2E
				: new byte[]{0x00, (byte) 0xB0, p1, p2, (byte) len});
		if (!Apdu.ok(r)) {
			throw new IllegalStateException(String.format(Locale.ROOT, "READ BINARY SW=%04X", Apdu.sw(r)));
		}
		return Apdu.data(r);
	}

	/**
	 * The chip's maximum response length from EF.ATR/INFO: tag 7F66 holds two
	 * INTEGERs, max command length then max response length.
	 *
	 * @return the limit, or 0 if 7F66 is absent or malformed
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static int maxResponseFrom(byte[] atrInfo) {
		try {
			for (final Tlv t : Tlv.parse(atrInfo, false)) {
				if (t.tag != 0x7F66) continue;
				final List<Tlv> ints = Tlv.findAll(Tlv.parse(t.value, false), 0x02);
				if (ints.size() < 2) return 0;
				int v = 0;
				for (final byte b : ints.get(1).value) v = (v << 8) | (b & 0xFF);
				return v;
			}
		} catch (IllegalArgumentException malformed) {
			// fall through: treat as "no extended length"
		}
		return 0;
	}

	/** Size of the whole file from its outer TLV header: tag (1 byte) + length + value. */
	static int totalLength(byte[] head) {
		int i = 1; // data-group tags are all one byte (0x60–0x77)
		if (i >= head.length) return head.length;
		final int b = head[i++] & 0xFF;
		int n;
		if ((b & 0x80) == 0) {
			n = b;
		} else {
			n = 0;
			for (int j = 0; j < (b & 0x7F) && i < head.length; ++j) n = (n << 8) | (head[i++] & 0xFF);
		}
		return i + n;
	}
}
