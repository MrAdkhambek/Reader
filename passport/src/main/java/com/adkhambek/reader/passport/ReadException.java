/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * A card read did not complete. {@link #reason()} says what the caller can do
 * about it.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public final class ReadException extends Exception {

	public enum Reason {
		/** The card left the field mid-read. Ask the user to hold it still and retry. */
		CARD_LOST,
		/** Passport only: Basic Access Control failed — almost always a mistyped document number or date. */
		AUTH_FAILED,
		/** Not a card this reader understands: not ISO-DEP, no eMRTD applet, or no PPSE. */
		UNSUPPORTED,
		/** Anything else: an unexpected status word or malformed data. */
		FAILED,
	}

	private final Reason reason;

	public ReadException(Reason reason, String message) {
		this(reason, message, null);
	}

	public ReadException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
