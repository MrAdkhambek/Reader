/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.iso7816;

import androidx.annotation.RestrictTo;

import java.io.IOException;

/**
 * One raw APDU exchange. In production this is {@code isoDep::transceive}; in
 * tests it is a scripted replay.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface Transceiver {
	byte[] transceive(byte[] apdu) throws IOException;
}
