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
}
