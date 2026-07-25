/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class ReplayChannelTest {

	@Test public void replaysScriptedExchangesInOrder() throws IOException {
		final ReplayChannel ch = new ReplayChannel()
				.expect("00A4040007A000000003101000").reply("6F0A8407A00000000310109000")
				.expect("80A80000023800").reply("77088002 1C009000".replace(" ", ""));

		assertArrayEquals(Hex.decode("6F0A8407A00000000310109000"),
				ch.transceive(Hex.decode("00A4040007A000000003101000")));
		assertArrayEquals(Hex.decode("770880021C009000"),
				ch.transceive(Hex.decode("80A80000023800")));
		ch.assertExhausted();
	}

	/** An unscripted APDU means the reader changed behaviour — fail loudly. */
	@Test public void unexpectedApduFails() {
		final ReplayChannel ch = new ReplayChannel()
				.expect("00A404000E325041592E5359532E4444463031 00".replace(" ", ""))
				.reply("6F009000");
		try {
			ch.transceive(Hex.decode("00B2010C00"));
			fail("expected AssertionError");
		} catch (AssertionError | IOException expected) {
			assertEquals(AssertionError.class, expected.getClass());
		}
	}

	/** Leftover scripted exchanges mean the reader stopped early. */
	@Test public void assertExhaustedFailsWhenExchangesRemain() throws IOException {
		final ReplayChannel ch = new ReplayChannel()
				.expect("00A4040007A000000003101000").reply("9000")
				.expect("80A80000023800").reply("9000");
		ch.transceive(Hex.decode("00A4040007A000000003101000"));
		try {
			ch.assertExhausted();
			fail("expected AssertionError");
		} catch (AssertionError expected) {
		}
	}

	/** StdTag must drive an ApduChannel, not a concrete IsoDep. */
	@Test public void stdTagChainsGetResponseOver61xx() throws IOException {
		final ReplayChannel ch = new ReplayChannel()
				.expect("00A4040007A000000003101000").reply("AABB6103")
				.expect("00C0000003").reply("CCDDEE9000");

		final Iso7816.Response r = new Iso7816.StdTag(ch)
				.transceive(Hex.decode("00A4040007A000000003101000"));

		assertEquals((short) 0x9000, r.getSw12());
		assertArrayEquals(Hex.decode("AABBCCDDEE"), r.getBytes());
		ch.assertExhausted();
	}
}
