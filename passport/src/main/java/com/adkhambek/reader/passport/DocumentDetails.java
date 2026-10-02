/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** DG12 — additional document details. Any field may be null. */
public record DocumentDetails(String issuingAuthority, String dateOfIssue, String endorsements) {
	// Redacted: toString() is what lands in logs. Only which fields are present.
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("DocumentDetails[");
		boolean first = true;
		final String[] names = {"issuingAuthority", "dateOfIssue", "endorsements"};
		final String[] values = {issuingAuthority, dateOfIssue, endorsements};
		for (int i = 0; i < names.length; ++i) {
			if (values[i] == null) continue;
			if (!first) sb.append(' ');
			sb.append(names[i]);
			first = false;
		}
		return sb.append(']').toString();
	}
}
