/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import androidx.annotation.RestrictTo;

import com.adkhambek.reader.passport.mrz.MrzDocument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One eMRTD read — immutable. Optional data groups that were absent or unreadable are null. */
public final class Passport {
	private final MrzDocument mrz;
	private final PersonalDetails personalDetails;
	private final DocumentDetails documentDetails;
	private final Map<String, String> nationalData;
	private final Photo photo;
	private final List<String> presentDataGroups;

	private Passport(Builder b) {
		this.mrz = b.mrz.build();
		this.personalDetails = b.personalDetails;
		this.documentDetails = b.documentDetails;
		this.nationalData = Collections.unmodifiableMap(new LinkedHashMap<>(b.nationalData));
		this.photo = b.photo;
		this.presentDataGroups = Collections.unmodifiableList(b.presentDataGroups);
	}

	/** DG1. Never null; {@link MrzDocument#hasMrz()} is false if DG1 could not be decoded. */
	public MrzDocument mrz() { return mrz; }

	/** DG11, or null. */
	public PersonalDetails personalDetails() { return personalDetails; }

	/** DG12, or null. */
	public DocumentDetails documentDetails() { return documentDetails; }

	/** DG13 issuer-defined entries keyed {@code tag_<hex>}. Never null; unmodifiable. */
	public Map<String, String> nationalData() { return nationalData; }

	/** DG2, or null. */
	public Photo photo() { return photo; }

	/** Data groups EF.COM lists, e.g. {@code "DG1"}. Never null; unmodifiable. */
	public List<String> presentDataGroups() { return presentDataGroups; }

	@Override
	public String toString() {
		// Redacted: identity data must not land in logs by accident.
		return "Passport[" + mrz + (photo == null ? "" : " +photo") + "]";
	}

	/** Accumulator the data-group parsers write into; not public API. */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static final class Builder {
		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public final MrzDocument.Builder mrz = new MrzDocument.Builder();
		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public final Map<String, String> nationalData = new LinkedHashMap<>();
		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public PersonalDetails personalDetails;
		@RestrictTo(RestrictTo.Scope.LIBRARY)
		public DocumentDetails documentDetails;
		Photo photo;
		List<String> presentDataGroups = Collections.emptyList();

		Passport build() {
			return new Passport(this);
		}
	}
}
