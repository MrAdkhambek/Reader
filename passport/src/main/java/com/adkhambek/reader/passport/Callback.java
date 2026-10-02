/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The outcome of one asynchronous read, delivered on the reader's callback
 * executor (the main thread by default). Exactly one method is called, once,
 * unless the read is cancelled first.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public interface Callback<T> {
	void onSuccess(T value);

	void onError(ReadException error);
}
