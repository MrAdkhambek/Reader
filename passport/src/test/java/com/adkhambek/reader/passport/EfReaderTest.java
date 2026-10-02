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
		assertNull(new EfReader(card, 0).read(FID_DG2));
		assertEquals(1, card.sent.size());
	}

	/** 1004 bytes: a 5-byte head read, then 223 × 4 + 107. */
	@Test public void readsWholeFileInShortChunks() throws Exception {
		final byte[] file = ef(1000);
		final FakeCard card = new FakeCard().put(FID_DG2, file);

		assertArrayEquals(file, new EfReader(card, 0).read(FID_DG2));
		assertEquals(7, card.sent.size());
		assertEquals("00B0000005", card.sent.get(1));
		assertEquals("00B00005DF", card.sent.get(2));
	}

	/** The header claims 1004 bytes but the card stops serving at 300. */
	@Test public void emptyChunkStopsTheLoop() throws Exception {
		final byte[] truncated = Arrays.copyOf(ef(1000), 300);
		final FakeCard card = new FakeCard().put(FID_DG2, truncated);

		assertArrayEquals(truncated, new EfReader(card, 0).read(FID_DG2));
	}

	/** Limit 4096 → chunk 4096 − 29 = 4067 (0x0FE3): 10004 bytes is a head read + 3 reads. */
	@Test public void extendedLimitUsesLargeChunks() throws Exception {
		final byte[] file = ef(10_000);
		final FakeCard card = new FakeCard().put(FID_DG2, file);

		assertArrayEquals(file, new EfReader(card, 4096).read(FID_DG2));
		assertEquals(5, card.sent.size());
		assertEquals("00B00005000FE3", card.sent.get(2));
	}

	/** A limit near short-form size buys nothing over 223, so stay short form. */
	@Test public void smallLimitStaysShortForm() throws Exception {
		final FakeCard card = new FakeCard().put(FID_DG2, ef(1000));

		new EfReader(card, 261).read(FID_DG2);
		assertEquals("00B00005DF", card.sent.get(2));
	}

	// --- EF.ATR/INFO: the chip's own limits, tag 7F66 ----------------------

	/** Two INTEGERs: max command (0400), then max response (0800 = 2048). */
	@Test public void maxResponseFromAtrInfo() {
		assertEquals(2048, EfReader.maxResponseFrom(Replay.hex("7F66080202040002020800")));
	}

	@Test public void maxResponseSkipsOtherObjects() {
		assertEquals(2048, EfReader.maxResponseFrom(Replay.hex("4703000000 7F66080202040002020800")));
	}

	@Test public void maxResponseIsZeroWhen7F66IsAbsent() {
		assertEquals(0, EfReader.maxResponseFrom(Replay.hex("4703000000")));
	}

	@Test public void maxResponseIsZeroWhenMalformed() {
		assertEquals(0, EfReader.maxResponseFrom(Replay.hex("7F66")));
	}
}
