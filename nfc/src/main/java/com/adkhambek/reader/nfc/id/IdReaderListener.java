/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id;

import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.common.mrz.IdCard;

/**
 * Typed callback for {@link IdReaderManager}. Replaces the legacy
 * {@code onReadEvent(EVENT, Object...)} varargs API with a {@link Result}-based
 * terminal event.
 */
public interface IdReaderListener {
	/** Reader has connected to the tag; the read is in progress. */
	default void onReading() {
	}

	/** Terminal event: a parsed {@link IdCard} or the exception that aborted the read. */
	void onResult(Result<IdCard, Exception> result);
}
