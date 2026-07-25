/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.mrz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an eMRTD read or an MRZ scan — immutable.
 *
 * <p>Every field is optional; absent data reads back as {@code null} (or an
 * empty collection). A card is assembled incrementally from several sources —
 * DG1 supplies the MRZ fields, DG11/DG12/DG13 add detail, DG2 the face image,
 * EF.COM the list of groups present — so construction goes through
 * {@link Builder} and the finished object never changes afterwards.
 *
 * <p>{@link #photoBytes()} returns the internal array without copying: face
 * images run to tens of kilobytes and copying them on every access is not worth
 * it. Treat it as read-only.
 */
public final class IdCard {

	// From DG1 (MRZ).
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
	private final String rawMrz;

	// From DG11 (additional personal details).
	private final String fullName;
	private final String otherNames;
	private final String placeOfBirth;
	private final String fullDateOfBirth;
	private final String address;
	private final String telephone;
	private final String profession;
	private final String title;

	// From DG12 (additional document details).
	private final String issuingAuthority;
	private final String dateOfIssue;
	private final String endorsements;

	// From DG13 (optional national data), DG2 (face) and EF.COM.
	private final Map<String, String> nationalData;
	private final byte[] photoBytes;
	private final PhotoFormat photoFormat;
	private final List<String> presentDataGroups;

	private IdCard(Builder b) {
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
		this.rawMrz = b.rawMrz;
		this.fullName = b.fullName;
		this.otherNames = b.otherNames;
		this.placeOfBirth = b.placeOfBirth;
		this.fullDateOfBirth = b.fullDateOfBirth;
		this.address = b.address;
		this.telephone = b.telephone;
		this.profession = b.profession;
		this.title = b.title;
		this.issuingAuthority = b.issuingAuthority;
		this.dateOfIssue = b.dateOfIssue;
		this.endorsements = b.endorsements;
		this.nationalData = Collections.unmodifiableMap(new LinkedHashMap<>(b.nationalData));
		this.photoBytes = b.photoBytes;
		this.photoFormat = b.photoFormat;
		this.presentDataGroups = (b.presentDataGroups == null)
				? Collections.emptyList()
				: Collections.unmodifiableList(b.presentDataGroups);
	}

	public String documentType() { return documentType; }

	public String issuingCountry() { return issuingCountry; }

	public String documentNumber() { return documentNumber; }

	public String lastName() { return lastName; }

	public String firstName() { return firstName; }

	public String sex() { return sex; }

	public String nationality() { return nationality; }

	/** {@code YYYY-MM-DD}, or the raw {@code YYMMDD} field if it wasn't a valid date. */
	public String dateOfBirth() { return dateOfBirth; }

	/** {@code YYYY-MM-DD}, or the raw {@code YYMMDD} field if it wasn't a valid date. */
	public String dateOfExpiry() { return dateOfExpiry; }

	public String personalNumber() { return personalNumber; }

	public String optionalData() { return optionalData; }

	/** The MRZ exactly as read, before field splitting. */
	public String rawMrz() { return rawMrz; }

	public String fullName() { return fullName; }

	public String otherNames() { return otherNames; }

	public String placeOfBirth() { return placeOfBirth; }

	public String fullDateOfBirth() { return fullDateOfBirth; }

	public String address() { return address; }

	public String telephone() { return telephone; }

	public String profession() { return profession; }

	public String title() { return title; }

	public String issuingAuthority() { return issuingAuthority; }

	public String dateOfIssue() { return dateOfIssue; }

	public String endorsements() { return endorsements; }

	/** Issuer-defined DG13 entries, keyed by BER tag. Never null; unmodifiable. */
	public Map<String, String> nationalData() { return nationalData; }

	/** DG2 face image, or null. Not copied — do not modify. */
	public byte[] photoBytes() { return photoBytes; }

	/** Encoding of {@link #photoBytes()}, or null when no DG2 was read. */
	public PhotoFormat photoFormat() { return photoFormat; }

	/** Data groups EF.COM says are present. Never null; unmodifiable. */
	public List<String> presentDataGroups() { return presentDataGroups; }

	/** True when a document number was decoded — i.e. this really is an MRZ. */
	public boolean hasMrz() { return documentNumber != null; }

	@Override
	public String toString() {
		// Deliberately terse: an IdCard holds high-value identity data and this
		// string is what lands in a log if someone prints it. Enough to tell two
		// reads apart, nothing more.
		return "IdCard[" + documentType + " " + issuingCountry
				+ (documentNumber == null ? "" : " ****")
				+ (photoBytes == null ? "" : " +photo")
				+ "]";
	}

	/**
	 * Mutable accumulator for an {@link IdCard}. Each data-group parser writes
	 * the fields it owns; the reader calls {@link #build()} once at the end.
	 */
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
		private String rawMrz;
		private String fullName;
		private String otherNames;
		private String placeOfBirth;
		private String fullDateOfBirth;
		private String address;
		private String telephone;
		private String profession;
		private String title;
		private String issuingAuthority;
		private String dateOfIssue;
		private String endorsements;
		private final Map<String, String> nationalData = new LinkedHashMap<>();
		private byte[] photoBytes;
		private PhotoFormat photoFormat;
		private List<String> presentDataGroups;

		public Builder documentType(String v) { this.documentType = v; return this; }

		public Builder issuingCountry(String v) { this.issuingCountry = v; return this; }

		public Builder documentNumber(String v) { this.documentNumber = v; return this; }

		public Builder lastName(String v) { this.lastName = v; return this; }

		public Builder firstName(String v) { this.firstName = v; return this; }

		public Builder sex(String v) { this.sex = v; return this; }

		public Builder nationality(String v) { this.nationality = v; return this; }

		public Builder dateOfBirth(String v) { this.dateOfBirth = v; return this; }

		public Builder dateOfExpiry(String v) { this.dateOfExpiry = v; return this; }

		public Builder personalNumber(String v) { this.personalNumber = v; return this; }

		/**
		 * Set the personal number only if DG1 didn't already supply one.
		 * DG11's 5F10 is a fallback for cards that leave the MRZ field blank; it
		 * must never overwrite the MRZ value.
		 */
		public Builder personalNumberIfAbsent(String v) {
			if (this.personalNumber == null) this.personalNumber = v;
			return this;
		}

		public Builder optionalData(String v) { this.optionalData = v; return this; }

		public Builder rawMrz(String v) { this.rawMrz = v; return this; }

		public Builder fullName(String v) { this.fullName = v; return this; }

		public Builder otherNames(String v) { this.otherNames = v; return this; }

		public Builder placeOfBirth(String v) { this.placeOfBirth = v; return this; }

		public Builder fullDateOfBirth(String v) { this.fullDateOfBirth = v; return this; }

		public Builder address(String v) { this.address = v; return this; }

		public Builder telephone(String v) { this.telephone = v; return this; }

		public Builder profession(String v) { this.profession = v; return this; }

		public Builder title(String v) { this.title = v; return this; }

		public Builder issuingAuthority(String v) { this.issuingAuthority = v; return this; }

		public Builder dateOfIssue(String v) { this.dateOfIssue = v; return this; }

		public Builder endorsements(String v) { this.endorsements = v; return this; }

		public Builder putNationalData(String tag, String value) {
			this.nationalData.put(tag, value);
			return this;
		}

		public Builder photo(PhotoFormat format, byte[] bytes) {
			this.photoFormat = format;
			this.photoBytes = bytes;
			return this;
		}

		public Builder presentDataGroups(List<String> v) { this.presentDataGroups = v; return this; }

		public IdCard build() {
			return new IdCard(this);
		}
	}
}
