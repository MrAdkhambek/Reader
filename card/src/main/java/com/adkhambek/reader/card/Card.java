/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.util.Collections;
import java.util.List;

/**
 * One tap of a contactless card: its NFC UID and the payment applications read
 * from it. {@code apps} is unmodifiable and never null.
 */
public record Card(String uid, List<CardApp> apps) {

	public Card(String uid, List<CardApp> apps) {
		this.uid = uid;
		this.apps = (apps != null) ? Collections.unmodifiableList(apps) : Collections.emptyList();
	}

	public boolean isUnknown() {
		return apps.isEmpty();
	}
}
