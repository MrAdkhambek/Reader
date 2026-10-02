/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The input is not a machine-readable zone this library can decode. Unchecked:
 * gate untrusted input on {@link Mrz#isValid} and this never happens.
 */
public final class MrzFormatException extends IllegalArgumentException {
	public MrzFormatException(String message) {
		super(message);
	}
}
