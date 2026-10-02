/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.mrz;

import androidx.annotation.RestrictTo;

/**
 * The fields of an ICAO 9303 machine-readable zone — immutable.
 *
 * <p>Absent fields are null. Dates are {@code YYYY-MM-DD}, or the raw
 * {@code YYMMDD} field when it was not a valid date.
 */
public final class MrzDocument {

	private final String documentType;
	private final String issuingCountry;
	private final String documentNumber;
	private final String lastName;
	private final String firstName;
	private final String sex;
	private final String nationality;
	private final String dateOfBirth;
	private final String dateOfExpiry;
	private final String personalNumber;
	private final String optionalData;
	private final String raw;

	private MrzDocument(Builder b) {
		this.documentType = b.documentType;
		this.issuingCountry = b.issuingCountry;
		this.documentNumber = b.documentNumber;
		this.lastName = b.lastName;
		this.firstName = b.firstName;
		this.sex = b.sex;
		this.nationality = b.nationality;
		this.dateOfBirth = b.dateOfBirth;
		this.dateOfExpiry = b.dateOfExpiry;
		this.personalNumber = b.personalNumber;
		this.optionalData = b.optionalData;
		this.raw = b.raw;
	}

	public String documentType() { return documentType; }

	public String issuingCountry() { return issuingCountry; }

	public String documentNumber() { return documentNumber; }

	public String lastName() { return lastName; }

	public String firstName() { return firstName; }

	public String sex() { return sex; }

	public String nationality() { return nationality; }

	public String dateOfBirth() { return dateOfBirth; }

	public String dateOfExpiry() { return dateOfExpiry; }

	public String personalNumber() { return personalNumber; }

	public String optionalData() { return optionalData; }

	/** The MRZ as decoded, with whitespace removed. */
	public String raw() { return raw; }

	/** True when a document number was decoded — i.e. this really is an MRZ. */
	public boolean hasMrz() { return documentNumber != null; }

	@Override
	public String toString() {
		// Redacted: an MRZ is high-value identity data and toString() is what lands in logs.
		return "MrzDocument[" + documentType + " " + issuingCountry
				+ (documentNumber == null ? "" : " ****") + "]";
	}

	/** Filled by {@link Mrz} and the DG11 parser; not public API. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static final class Builder {
		private String documentType;
		private String issuingCountry;
		private String documentNumber;
		private String lastName;
		private String firstName;
		private String sex;
		private String nationality;
		private String dateOfBirth;
		private String dateOfExpiry;
		private String personalNumber;
		private String optionalData;
		private String raw;

		Builder documentType(String v) { this.documentType = v; return this; }

		Builder issuingCountry(String v) { this.issuingCountry = v; return this; }

		Builder documentNumber(String v) { this.documentNumber = v; return this; }

		Builder lastName(String v) { this.lastName = v; return this; }

		Builder firstName(String v) { this.firstName = v; return this; }

		Builder sex(String v) { this.sex = v; return this; }

		Builder nationality(String v) { this.nationality = v; return this; }

		Builder dateOfBirth(String v) { this.dateOfBirth = v; return this; }

		Builder dateOfExpiry(String v) { this.dateOfExpiry = v; return this; }

		Builder personalNumber(String v) { this.personalNumber = v; return this; }

		/** DG11's 5F10 is a fallback for a blank MRZ field; it must not overwrite DG1. */
		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public Builder personalNumberIfAbsent(String v) {
			if (this.personalNumber == null) this.personalNumber = v;
			return this;
		}

		Builder optionalData(String v) { this.optionalData = v; return this; }

		Builder raw(String v) { this.raw = v; return this; }

		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public MrzDocument build() { return new MrzDocument(this); }
	}
}
