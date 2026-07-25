/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import java.io.Closeable;
import java.io.IOException;

/**
 * A bidirectional APDU pipe to a smart card.
 *
 * <p>The readers talk to this interface rather than to
 * {@link android.nfc.tech.IsoDep} directly, so a recorded card transcript can
 * be replayed in a unit test. {@link IsoDepChannel} is the production
 * implementation; {@code ReplayChannel} (test fixtures) is the test one.
 *
 * <p>Implementations carry no chaining logic — 61xx/6Cxx handling lives in
 * {@link Iso7816.StdTag}, which decorates a channel.
 */
public interface ApduChannel extends Closeable {

	/** Open the connection. Idempotent implementations are not required. */
	void connect() throws IOException;

	/** Send one command APDU and return the raw response, SW1 SW2 included. */
	byte[] transceive(byte[] apdu) throws IOException;

	/** Best-effort transceive timeout. Implementations may ignore this. */
	void setTimeout(int millis);
}
