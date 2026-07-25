/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.card.reader;

import com.adkhambek.reader.nfc.card.bean.Card;
import com.adkhambek.reader.common.Result;

/**
 * Typed callback for {@link ReaderManager}. Replaces the legacy
 * {@code onReadEvent(EVENT, Object...)} varargs API with a {@link Result}-based
 * terminal event.
 */
public interface ReaderListener {
	/** Reader has connected to the tag; the read is in progress. */
	default void onReading() {
	}

	/** Terminal event: a parsed {@link Card} or the exception that aborted the read. */
	void onResult(Result<Card, Exception> result);
}
