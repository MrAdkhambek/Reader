/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card.iso7816;

import static org.junit.Assert.fail;

import org.junit.Test;

/** The fixture must fail loudly, or every transcript test could pass vacuously. */
public class ReplayTest {

	@Test public void unexpectedApduFails() {
		final Replay card = new Replay().expect("00A4040000").reply("9000");
		try {
			card.transceive(Replay.hex("00B2010C00"));
			fail("expected AssertionError");
		} catch (AssertionError expected) {
		}
	}

	@Test public void leftoverExchangesFail() {
		final Replay card = new Replay()
				.expect("00A4040000").reply("9000")
				.expect("00B2010C00").reply("9000");
		card.transceive(Replay.hex("00A4040000"));
		try {
			card.assertExhausted();
			fail("expected AssertionError");
		} catch (AssertionError expected) {
		}
	}
}
