/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Reads one elementary file: SELECT by FID, then READ BINARY in chunks. */
final class EfReader {
	/** 223 data bytes plus SM overhead fit a 256-byte short response. */
	static final int SHORT_CHUNK = 0xDF;
	/** Tag + up to 0x83 + 3 length bytes: enough to size any file up to 16 MB. */
	static final int HEAD_LEN = 5;

	private final Transceiver sm;

	/** @param sm plain APDU in, unwrapped {@code data ‖ SW} out */
	EfReader(Transceiver sm) {
		this.sm = sm;
	}

	/** @return the whole file, or null if the chip says it does not exist (6A82) */
	byte[] read(int fid) throws IOException {
		final byte[] sel = sm.transceive(
				new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, (byte) (fid >> 8), (byte) fid});
		final int sw = Apdu.sw(sel);
		if (sw == 0x6A82) return null;
		if (sw != Apdu.SW_OK) {
			throw new IllegalStateException(String.format(Locale.ROOT, "SELECT %04X SW=%04X", fid, sw));
		}

		final byte[] head = readBinary(0, HEAD_LEN);
		final int total = totalLength(head);
		final ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.write(head, 0, head.length);
		int off = head.length;
		while (off < total) {
			final byte[] part = readBinary(off, Math.min(SHORT_CHUNK, total - off));
			// A 9000 with no bytes (EOF quirk) would otherwise spin forever.
			if (part.length == 0) break;
			all.write(part, 0, part.length);
			off += part.length;
		}
		return all.toByteArray();
	}

	private byte[] readBinary(int offset, int len) throws IOException {
		// B0 with P1 bit 8 clear addresses the current EF with a 15-bit offset.
		if (offset > 0x7FFF) {
			throw new IllegalStateException("files over 32 KB are not supported (offset " + offset + ")");
		}
		final byte[] r = sm.transceive(
				new byte[]{0x00, (byte) 0xB0, (byte) (offset >> 8), (byte) offset, (byte) len});
		if (!Apdu.ok(r)) {
			throw new IllegalStateException(String.format(Locale.ROOT, "READ BINARY SW=%04X", Apdu.sw(r)));
		}
		return Apdu.data(r);
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
