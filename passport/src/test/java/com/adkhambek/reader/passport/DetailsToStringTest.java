/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class DetailsToStringTest {

	@Test public void personalDetailsToStringHasNoValues() {
		final String s = new PersonalDetails("ANNA MARIA ERIKSSON", "MARIA", "STOCKHOLM",
				"19740812", "1 HIGH STREET", "+46123456", "ENGINEER", "DR").toString();
		for (final String v : new String[]{"ANNA", "ERIKSSON", "MARIA", "STOCKHOLM", "19740812",
				"HIGH STREET", "+46123456", "ENGINEER", "DR"}) {
			assertFalse(s, s.contains(v));
		}
	}

	@Test public void documentDetailsToStringHasNoValues() {
		final String s = new DocumentDetails("STOCKHOLM POLICE", "20200101", "NONE ENDORSED").toString();
		for (final String v : new String[]{"STOCKHOLM", "POLICE", "20200101", "ENDORSED"}) {
			assertFalse(s, s.contains(v));
		}
	}
}
