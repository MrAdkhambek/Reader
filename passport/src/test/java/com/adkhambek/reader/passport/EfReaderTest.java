/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EfReaderTest {

	static final int FID_DG2 = 0x0102;

	/**
	 * Serves EFs from memory over plain (already unwrapped) APDUs and records
	 * every command. Reads past the stored bytes return 9000 with no data —
	 * the EOF quirk EfReader must survive.
	 */
	static final class FakeCard implements Transceiver {
		final List<String> sent = new ArrayList<>();
		private final Map<Integer, byte[]> files = new HashMap<>();
		private byte[] current;

		FakeCard put(int fid, byte[] content) {
			files.put(fid, content);
			return this;
		}

		@Override
		public byte[] transceive(byte[] apdu) {
			sent.add(Bytes.hex(apdu));
			if (apdu[1] == (byte) 0xA4) {
				current = files.get(((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF));
				return current == null ? Replay.hex("6A82") : Replay.hex("9000");
			}
			if (apdu[1] == (byte) 0xB0) {
				final int offset = ((apdu[2] & 0x7F) << 8) | (apdu[3] & 0xFF);
				final int le = (apdu.length == 5)
						? orMax(apdu[4] & 0xFF, 256)
						: orMax(((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF), 65536);
				final int n = Math.max(0, Math.min(le, current.length - offset));
				return Bytes.concat(Arrays.copyOfRange(current, offset, offset + n), Replay.hex("9000"));
			}
			throw new AssertionError("unexpected INS in " + Bytes.hex(apdu));
		}

		private static int orMax(int v, int max) {
			return v == 0 ? max : v;
		}
	}

	/** A DG2-shaped EF: tag 75, long-form length, patterned body. */
	static byte[] ef(int bodyLen) {
		final byte[] body = new byte[bodyLen];
		for (int i = 0; i < bodyLen; ++i) body[i] = (byte) i;
		return SecureMessaging.tlv(0x75, body);
	}

	@Test public void absentFileIsNullAfterOneSelect() throws Exception {
		final FakeCard card = new FakeCard();
		assertNull(new EfReader(card).read(FID_DG2));
		assertEquals(1, card.sent.size());
	}

	/** 1004 bytes: a 5-byte head read, then 223 × 4 + 107. */
	@Test public void readsWholeFileInShortChunks() throws Exception {
		final byte[] file = ef(1000);
		final FakeCard card = new FakeCard().put(FID_DG2, file);

		assertArrayEquals(file, new EfReader(card).read(FID_DG2));
		assertEquals(7, card.sent.size());
		assertEquals("00B0000005", card.sent.get(1));
		assertEquals("00B00005DF", card.sent.get(2));
	}

	/** The header claims 1004 bytes but the card stops serving at 300. */
	@Test public void emptyChunkStopsTheLoop() throws Exception {
		final byte[] truncated = Arrays.copyOf(ef(1000), 300);
		final FakeCard card = new FakeCard().put(FID_DG2, truncated);

		assertArrayEquals(truncated, new EfReader(card).read(FID_DG2));
	}
}
