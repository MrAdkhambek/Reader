/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CardAppTest {

	@Test public void toStringRedactsPanAndOmitsCardholder() {
		final CardApp app = new CardApp(new byte[]{(byte) 0xA0, 0x00}, "VISA",
				"4111 1111 1111 5678", "01", "ERIKSSON ANNA", "0840", Currency.USD,
				"2026.01", "2027.12", "0001");
		final String s = app.toString();
		assertFalse(s, s.contains("4111"));
		assertFalse(s, s.contains("1111 1111"));
		assertFalse(s, s.contains("ERIKSSON"));
		assertFalse(s, s.contains("ANNA"));
		assertTrue(s, s.contains("5678"));
	}
}
