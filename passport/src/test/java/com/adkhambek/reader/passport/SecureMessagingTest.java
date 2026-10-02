/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** D.4 tests: ICAO 9303-11 Appendix D.4, Secure Messaging after the D.3 BAC example. */
public class SecureMessagingTest {

	private static final byte[] KS_ENC = Replay.hex("979EC13B1CBFE9DCD01AB0FED307EAE5");
	private static final byte[] KS_MAC = Replay.hex("F1CB1F1FB5ADF208806B89DC579DC1F8");
	private static final byte[] SSC = Replay.hex("887022120C06C226");

	private static final String SELECT_COM = "00A4020C02011E";
	private static final String SELECT_COM_RESPONSE = "990290008E08FA855A5D4C50A8ED";

	private static SecureMessaging sm() {
		return new SecureMessaging(KS_ENC, KS_MAC, SSC);
	}

	@Test public void d4_wrapsSelectEfCom() {
		assertEquals("0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800",
				Bytes.hex(sm().wrap(Replay.hex(SELECT_COM))));
	}

	@Test public void d4_unwrapsSelectResponse() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		assertEquals("9000", Bytes.hex(sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000)));
	}

	@Test public void d4_wrapsReadBinaryAfterSelect() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000);
		assertEquals("0CB000000D9701048E08ED6705417E96BA5500",
				Bytes.hex(sm.wrap(Replay.hex("00B0000004"))));
	}

	@Test public void d4_unwrapsReadBinaryResponse() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000);
		sm.wrap(Replay.hex("00B0000004"));
		assertEquals("60145F019000", Bytes.hex(sm.unwrap(
				Replay.hex("8709019FF0EC34F9922651990290008E08AD55CC17140B2DED"), 0x9000)));
	}

	/** Case 2E: Le 2048 → extended Lc 000E (DO97 4 + DO8E 10), extended Le 0000. */
	@Test public void extendedLeProducesAnExtendedApdu() {
		final byte[] out = sm().wrap(Replay.hex("00B00000000800"));
		assertTrue(Bytes.hex(out).startsWith("0CB0000000000E97020800"));
		assertTrue(Bytes.hex(out).endsWith("0000"));
		assertEquals(4 + 3 + 14 + 2, out.length);
	}

	/** Review focus: a bare error status with no SM objects is passed through, not a MAC failure. */
	@Test public void unprotectedErrorStatusPassesThrough() {
		assertArrayEquals(Replay.hex("6A82"), sm().unwrap(new byte[0], 0x6A82));
	}

	@Test public void tlvEncodesLongFormLengths() {
		assertEquals("8E0101", Bytes.hex(SecureMessaging.tlv(0x8E, Replay.hex("01"))));
		assertEquals("8781C8", Bytes.hex(SecureMessaging.tlv(0x87, new byte[200])).substring(0, 6));
		assertEquals("8782012C", Bytes.hex(SecureMessaging.tlv(0x87, new byte[300])).substring(0, 8));
	}
}
