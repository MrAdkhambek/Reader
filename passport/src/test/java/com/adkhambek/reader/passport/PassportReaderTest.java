/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.adkhambek.reader.passport.iso7816.Replay;
import com.adkhambek.reader.passport.iso7816.Transceiver;
import com.adkhambek.reader.passport.mrz.MrzKey;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

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
			PassportReader.readWith(card, KEY, 0);
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
			PassportReader.readWith(new Replay(), null, 0);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	/** With a phone that supports extended length, EF.ATR/INFO is probed first —
	 *  unprotected, before the applet is selected — and its absence is harmless. */
	@Test public void extendedLengthProbeRunsFirstAndToleratesAbsence() {
		final Replay card = new Replay()
				.expect("00A4020C022F01").reply("6A82")
				.expect(SELECT_EMRTD).reply("6A82");
		try {
			PassportReader.readWith(card, KEY, 65279);
			fail("expected ReadException");
		} catch (ReadException e) {
			assertEquals(ReadException.Reason.UNSUPPORTED, e.reason());
		}
		card.assertExhausted();
	}

	@Test public void unreadableComReadsEveryOptionalGroup() {
		assertEquals(Arrays.asList(0x010B, 0x010C, 0x010D, 0x0102), PassportReader.optionalFids(null));
	}

	@Test public void emptyComReadsEveryOptionalGroup() {
		assertEquals(Arrays.asList(0x010B, 0x010C, 0x010D, 0x0102),
				PassportReader.optionalFids(Collections.emptyList()));
	}

	@Test public void readsOnlyGroupsComLists() {
		assertEquals(Collections.singletonList(0x0102),
				PassportReader.optionalFids(Arrays.asList("DG1", "DG2")));
	}
}
