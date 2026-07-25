/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.mrz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an eMRTD read. All fields are optional except by-construction
 * invariants; absent data is left null. {@link #presentDataGroups} comes from
 * EF.COM. {@link #photoBytes}/{@link #photoFormat} come from DG2.
 */
public final class IdCard {
	// From DG1 (MRZ).
	public String documentType;
	public String issuingCountry;
	public String documentNumber;
	public String lastName;
	public String firstName;
	public String sex;
	public String nationality;
	public String dateOfBirth;
	public String dateOfExpiry;
	public String personalNumber;
	public String optionalData;
	public String rawMrz;

	// From DG11 (additional personal details).
	public String fullName;
	public String otherNames;
	public String placeOfBirth;
	public String fullDateOfBirth;
	public String address;
	public String telephone;
	public String profession;
	public String title;

	// From DG12 (additional document details).
	public String issuingAuthority;
	public String dateOfIssue;
	public String endorsements;

	// From DG13 (optional national data).
	public Map<String, String> nationalData = new LinkedHashMap<>();

	// From DG2 (face).
	public byte[] photoBytes;
	public PhotoFormat photoFormat;

	// From EF.COM (5C list of data-group tags present on the card).
	public List<String> presentDataGroups;
}
