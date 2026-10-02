/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Sends a command APDU and follows the ISO 7816-4 status-word protocol:
 * 61xx → GET RESPONSE for the remaining bytes; 6Cxx → resend with the
 * corrected Le. A response is {@code data ‖ SW1 SW2}.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Apdu {
	static final int SW_OK = 0x9000;
	private static final int MAX_EXCHANGES = 16;
	private static final byte[] ERROR = {0x6F, 0x00};

	private final Transceiver transceiver;

	Apdu(Transceiver transceiver) {
		this.transceiver = transceiver;
	}

	byte[] send(byte[] command) throws IOException {
		final ByteArrayOutputStream data = new ByteArrayOutputStream();
		byte[] c = command.clone();
		for (int i = 0; i < MAX_EXCHANGES; ++i) {
			final byte[] r = transceiver.transceive(c);
			if (r == null || r.length < 2) return ERROR.clone();
			final int n = r.length - 2;
			final byte sw1 = r[n];
			final byte sw2 = r[n + 1];
			if (sw1 == 0x6C) {
				// Wrong Le: resend with the one the card asked for, keep nothing.
				c[c.length - 1] = sw2;
				continue;
			}
			data.write(r, 0, n);
			if (sw1 != 0x61) {
				data.write(sw1);
				data.write(sw2);
				return data.toByteArray();
			}
			// 61xx: sw2 more bytes are waiting.
			c = new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, sw2};
		}
		throw new IllegalStateException("too many chained responses");
	}

	static int sw(byte[] response) {
		final int n = response.length;
		return ((response[n - 2] & 0xFF) << 8) | (response[n - 1] & 0xFF);
	}

	static boolean ok(byte[] response) {
		return sw(response) == SW_OK;
	}

	/** The response without its status word. */
	static byte[] data(byte[] response) {
		return Arrays.copyOf(response, response.length - 2);
	}
}
