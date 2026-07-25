/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id;

import java.util.Locale;

/**
 * The three MRZ fields needed to derive a BAC session key:
 * document number, date of birth, date of expiry. Dates are YYMMDD.
 */
public final class MrzKey {
	public final String documentNumber;
	public final String dateOfBirth;
	public final String dateOfExpiry;

	public MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry) {
		if (documentNumber == null || dateOfBirth == null || dateOfExpiry == null) {
			throw new IllegalArgumentException("MRZ key fields must be non-null");
		}
		if (dateOfBirth.length() != 6) {
			throw new IllegalArgumentException("dateOfBirth must be 6 digits YYMMDD");
		}
		if (dateOfExpiry.length() != 6) {
			throw new IllegalArgumentException("dateOfExpiry must be 6 digits YYMMDD");
		}
		this.documentNumber = documentNumber.trim().toUpperCase(Locale.ROOT);
		this.dateOfBirth = dateOfBirth.trim();
		this.dateOfExpiry = dateOfExpiry.trim();
	}
}
