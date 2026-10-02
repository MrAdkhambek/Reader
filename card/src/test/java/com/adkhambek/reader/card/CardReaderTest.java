/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/** SYNTHETIC transcripts: a one-application Visa card built to the EMV structure. */
public class CardReaderTest {

	private static final String SELECT_PPSE = "00A404000E325041592E5359532E444446303100";
	private static final String PPSE_FCI =
			"6F20 840E325041592E5359532E4444463031 A50E BF0C0B 6109 4F07A0000000031010 9000";
	private static final String SELECT_VISA = "00A4040007A000000003101000";
	private static final String VISA_FCI = "6F11 8407A0000000031010 A506 500456495341 9000";
	private static final String GPO_NO_PDOL = "80A8000002830000";
	/** PAN 4111…1111, expiry 5F24 = 27-12-31. */
	private static final String RECORD = "7010 5A084111111111111111 5F2403271231 9000";

	private static ReadException.Reason reasonOf(Transceiver card) {
		try {
			CardReader.readWith("04A1B2C3", card);
			fail("expected ReadException");
			return null;
		} catch (ReadException e) {
			return e.reason();
		}
	}

	@Test public void readsAnApplication() throws ReadException {
		final Replay card = new Replay()
				.expect(SELECT_PPSE).reply(PPSE_FCI)
				.expect(SELECT_VISA).reply(VISA_FCI)
				.expect(GPO_NO_PDOL).reply("8006 1800 08010100 9000")
				.expect("00B2010C00").reply(RECORD);

		final Card result = CardReader.readWith("04A1B2C3", card);

		card.assertExhausted();
		assertEquals("04A1B2C3", result.uid());
		assertEquals(1, result.apps().size());
		final CardApp app = result.apps().get(0);
		assertEquals("VISA", app.label());
		assertEquals("4111 1111 1111 1111", app.pan());
		assertEquals("2027.12", app.expiryDate());
	}

	/** Review focus: a malformed record is skipped; the PAN comes from the next one. */
	@Test public void malformedRecordIsSkipped() throws ReadException {
		final Replay card = new Replay()
				.expect(SELECT_PPSE).reply(PPSE_FCI)
				.expect(SELECT_VISA).reply(VISA_FCI)
				.expect(GPO_NO_PDOL).reply("8006 1800 08010200 9000")
				.expect("00B2010C00").reply("7004 5A084111 9000")
				.expect("00B2020C00").reply(RECORD);

		final Card result = CardReader.readWith("04A1B2C3", card);

		assertEquals("4111 1111 1111 1111", result.apps().get(0).pan());
	}

	@Test public void noPpseIsUnsupported() {
		final Replay card = new Replay().expect(SELECT_PPSE).reply("6A82");
		assertEquals(ReadException.Reason.UNSUPPORTED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void lostCardIsCardLost() {
		assertEquals(ReadException.Reason.CARD_LOST, reasonOf(apdu -> {
			throw new IOException("Tag was lost.");
		}));
	}

	/** Review M5: a malformed GPO/FCI on one AID skips that AID, not the whole card. */
	@Test public void malformedAidIsSkipped() throws ReadException {
		final String ppse = "6F2C 840E325041592E5359532E4444463031 A51A BF0C17"
				+ " 6109 4F07A0000000031010 610A 4F08A000000004101012 9000";
		final Replay card = new Replay()
				.expect(SELECT_PPSE).reply(ppse)
				.expect(SELECT_VISA).reply(VISA_FCI)
				.expect(GPO_NO_PDOL).reply("8006 1800 08010100 9000")
				.expect("00B2010C00").reply(RECORD);
		// second AID: FCI carries a PDOL whose tag has no length byte.
		card.expect("00A4040008A00000000410101200").reply("6F07 A505 9F38029F 9000");
		final Card result = CardReader.readWith("04A1B2C3", card);
		assertEquals(1, result.apps().size());
	}
}
