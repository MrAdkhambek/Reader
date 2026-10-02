/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class ApduTest {

	private static final String SELECT = "00A4040007A000000003101000";

	@Test public void passesFinalResponseThrough() throws IOException {
		final Replay card = new Replay().expect(SELECT).reply("AABB9000");
		assertArrayEquals(Replay.hex("AABB9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	/** 61xx: fetch the rest with GET RESPONSE and append it. */
	@Test public void chains61xxWithGetResponse() throws IOException {
		final Replay card = new Replay()
				.expect(SELECT).reply("AABB6103")
				.expect("00C0000003").reply("CCDDEE9000");
		assertArrayEquals(Replay.hex("AABBCCDDEE9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	/** 6Cxx: resend the same command with Le corrected; the 6Cxx exchange adds nothing. */
	@Test public void retries6CxxWithCorrectedLe() throws IOException {
		final Replay card = new Replay()
				.expect(SELECT).reply("6C05")
				.expect("00A4040007A000000003101005").reply("AABBCCDDEE9000");
		assertArrayEquals(Replay.hex("AABBCCDDEE9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	@Test public void capsChainingAt16Exchanges() {
		final int[] calls = {0};
		final Apdu apdu = new Apdu(command -> {
			++calls[0];
			return Replay.hex("6100");
		});
		try {
			apdu.send(Replay.hex("00B0000000"));
			fail("expected IOException");
		} catch (IOException expected) {
		}
		assertEquals(16, calls[0]);
	}

	@Test public void shortResponseReadsAs6F00() throws IOException {
		final Apdu apdu = new Apdu(command -> new byte[]{(byte) 0x90});
		assertArrayEquals(Replay.hex("6F00"), apdu.send(Replay.hex(SELECT)));
	}

	@Test public void statusWordHelpers() {
		final byte[] r = Replay.hex("AABB6A82");
		assertEquals(0x6A82, Apdu.sw(r));
		assertFalse(Apdu.ok(r));
		assertArrayEquals(Replay.hex("AABB"), Apdu.data(r));
		assertTrue(Apdu.ok(Replay.hex("9000")));
	}
}
