/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

/**
 * The card rejected the credentials offered.
 *
 * <p>Thrown by the eMRTD reader when Basic Access Control fails. In practice
 * that almost always means the user mistyped the document number, date of
 * birth or date of expiry — not that the card is faulty. Say that in the UI
 * rather than surfacing a MAC error.
 */
public final class AuthenticationException extends ReadException {
	public AuthenticationException(String message) {
		super(message);
	}
}
