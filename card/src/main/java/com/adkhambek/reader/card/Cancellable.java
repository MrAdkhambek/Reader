/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

/**
 * Handle to one asynchronous read.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public interface Cancellable {
	/**
	 * Stop the read and drop its callback. Closes the card connection, so a read
	 * in progress fails fast — and so does any other code using the same tag at
	 * that moment. Idempotent; does nothing once the callback has fired.
	 *
	 * <p>Called on the callback executor's thread (the main thread by default),
	 * the callback is guaranteed not to fire afterwards. Called from another
	 * thread, a delivery already running there may still complete.
	 */
	void cancel();
}
