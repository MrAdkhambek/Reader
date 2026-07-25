/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

/** The tag left the field before the read finished. Ask the user to try again. */
public final class CardLostException extends ReadException {
	public CardLostException(String message, Throwable cause) {
		super(message, cause);
	}
}
