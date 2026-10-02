/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Review focus: input BAC cannot encode is rejected before any card I/O. */
public class MrzKeyTest {

	private static void assertRejected(String doc, String dob, String exp) {
		try {
			new MrzKey(doc, dob, exp);
			fail("expected IllegalArgumentException for " + doc + "/" + dob + "/" + exp);
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test public void upperCasesAndTrims() {
		final MrzKey key = new MrzKey(" l898902c< ", "690806", "940623");
		assertEquals("L898902C<", key.documentNumber);
	}

	@Test public void rejectsPunctuationAndInnerSpaces() {
		assertRejected("AB-123", "690806", "940623");
		assertRejected("AB 123", "690806", "940623");
	}

	@Test public void rejectsDatesThatAreNotSixDigits() {
		assertRejected("L898902C<", "69-08-06", "940623");
		assertRejected("L898902C<", "690806", "9406");
	}

	@Test public void rejectsNulls() {
		assertRejected(null, "690806", "940623");
	}
}
