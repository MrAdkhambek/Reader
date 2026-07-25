/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.mrz;

/**
 * Photo encoding produced by an eMRTD DG2 read. Lives in common so {@link IdCard}
 * can carry it without depending on the NFC reader module that does the extraction.
 */
public enum PhotoFormat {
	JPEG, JP2, UNKNOWN,
}
