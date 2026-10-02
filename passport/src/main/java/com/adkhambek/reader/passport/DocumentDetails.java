/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** DG12 — additional document details. Any field may be null. */
public record DocumentDetails(String issuingAuthority, String dateOfIssue, String endorsements) {
}
