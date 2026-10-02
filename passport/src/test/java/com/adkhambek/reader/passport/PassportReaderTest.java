/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * SYNTHETIC transcripts. BAC's cryptography is pinned by BacTest and
 * SecureMessagingTest against ICAO vectors; these cover the wiring and the
 * error mapping. A full happy path is not replayable: BAC uses a fresh
 * random RND.IFD and K.IFD on every run.
 */
public class PassportReaderTest {

	private static final String SELECT_EMRTD = "00A4040C07A0000002471001";
	private static final MrzKey KEY = new MrzKey("L898902C<", "690806", "940623");

	private static ReadException.Reason reasonOf(Transceiver card) {
		try {
			PassportReader.readWith(card, KEY);
			fail("expected ReadException");
			return null;
		} catch (ReadException e) {
			return e.reason();
		}
	}

	@Test public void missingAppletIsUnsupported() {
		final Replay card = new Replay().expect(SELECT_EMRTD).reply("6A82");
		assertEquals(ReadException.Reason.UNSUPPORTED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void refusedChallengeIsAuthFailed() {
		final Replay card = new Replay()
				.expect(SELECT_EMRTD).reply("9000")
				.expect("0084000008").reply("6982");
		assertEquals(ReadException.Reason.AUTH_FAILED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void lostCardIsCardLost() {
		assertEquals(ReadException.Reason.CARD_LOST, reasonOf(apdu -> {
			throw new IOException("Tag was lost.");
		}));
	}

	@Test public void nullKeyIsAProgrammingError() throws ReadException {
		try {
			PassportReader.readWith(new Replay(), null);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}
}
