/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id;

import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.common.mrz.IdCard;

/**
 * Typed callback for {@link IdReaderManager}. Same shape as
 * {@link com.adkhambek.reader.nfc.card.reader.ReaderListener} — an optional
 * in-progress hook plus one terminal {@code Result<T, Throwable>}.
 */
public interface IdReaderListener {
	/** Reader has connected to the tag; the read is in progress. */
	default void onReading() {
	}

	/** Terminal event: a parsed {@link IdCard}, or whatever aborted the read. */
	void onResult(Result<IdCard, Throwable> result);
}
