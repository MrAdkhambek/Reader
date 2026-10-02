/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** DG11 — additional personal details. Any field may be null. */
public record PersonalDetails(
		String fullName,
		String otherNames,
		String placeOfBirth,
		String fullDateOfBirth,
		String address,
		String telephone,
		String profession,
		String title
) {
	// Redacted: toString() is what lands in logs. Only which fields are present.
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("PersonalDetails[");
		boolean first = true;
		final String[] names = {"fullName", "otherNames", "placeOfBirth", "fullDateOfBirth",
				"address", "telephone", "profession", "title"};
		final String[] values = {fullName, otherNames, placeOfBirth, fullDateOfBirth,
				address, telephone, profession, title};
		for (int i = 0; i < names.length; ++i) {
			if (values[i] == null) continue;
			if (!first) sb.append(' ');
			sb.append(names[i]);
			first = false;
		}
		return sb.append(']').toString();
	}
}
