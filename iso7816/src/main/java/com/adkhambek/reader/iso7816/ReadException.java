/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

/**
 * A card read did not complete.
 *
 * <p>Checked, because every subclass is a condition a caller can act on: ask
 * the user to hold the card still, correct their input, or tell them the card
 * is not supported. Kotlin has no checked exceptions, so the ktx layer is
 * unaffected.
 *
 * <p>Declared here rather than in each reader so a consumer using both can
 * write one catch block.
 */
public class ReadException extends Exception {
	public ReadException(String message) {
		super(message);
	}

	public ReadException(String message, Throwable cause) {
		super(message, cause);
	}
}
