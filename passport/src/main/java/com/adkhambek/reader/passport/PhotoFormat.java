/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** Encoding of the DG2 face image. JPEG 2000 is common and Android cannot decode it natively. */
public enum PhotoFormat {
	JPEG, JP2, UNKNOWN,
}
