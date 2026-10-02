/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.mrz;

import java.util.Locale;

/**
 * The three MRZ fields Basic Access Control derives its keys from. Printed on
 * the document's data page.
 */
public final class MrzKey {
	public final String documentNumber;
	public final String dateOfBirth;
	public final String dateOfExpiry;

	/**
	 * @param documentNumber A–Z, 0–9 or {@code <}; lower case is accepted and upper-cased
	 * @param dateOfBirth    {@code YYMMDD}
	 * @param dateOfExpiry   {@code YYMMDD}
	 * @throws IllegalArgumentException if a field could not appear in an MRZ
	 */
	public MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry) {
		if (documentNumber == null || dateOfBirth == null || dateOfExpiry == null) {
			throw new IllegalArgumentException("MRZ key fields must be non-null");
		}
		final String doc = documentNumber.trim().toUpperCase(Locale.ROOT);
		final String dob = dateOfBirth.trim();
		final String exp = dateOfExpiry.trim();
		if (!doc.matches("[A-Z0-9<]+")) {
			throw new IllegalArgumentException("document number may contain only A-Z, 0-9 and <");
		}
		if (!dob.matches("[0-9]{6}")) throw new IllegalArgumentException("dateOfBirth must be YYMMDD");
		if (!exp.matches("[0-9]{6}")) throw new IllegalArgumentException("dateOfExpiry must be YYMMDD");
		this.documentNumber = doc;
		this.dateOfBirth = dob;
		this.dateOfExpiry = exp;
	}
}
