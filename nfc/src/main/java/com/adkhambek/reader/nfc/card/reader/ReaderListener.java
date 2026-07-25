/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.card.reader;

import com.adkhambek.reader.nfc.card.bean.Card;
import com.adkhambek.reader.common.Result;

/**
 * Typed callback for {@link ReaderManager}.
 *
 * <p>Every low-level reader in this library reports the same way: an optional
 * in-progress hook plus one terminal {@code onResult(Result<T, Throwable>)}.
 * The error side is {@code Throwable}, not {@code Exception}, so anything the
 * reader thread throws arrives intact rather than re-wrapped.
 *
 * <p>The drop-in views in {@code :qr} use the other convention —
 * {@code onScanned(T)} / {@code onError(Throwable)} — because a view callback
 * is normally a lambda and shouldn't force the caller to unpack a Result.
 */
public interface ReaderListener {
	/** Reader has connected to the tag; the read is in progress. */
	default void onReading() {
	}

	/** Terminal event: a parsed {@link Card}, or whatever aborted the read. */
	void onResult(Result<Card, Throwable> result);
}
