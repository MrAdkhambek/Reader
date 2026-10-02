/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Reads EMV contactless bank cards over NFC.
 *
 * <pre>{@code
 * CardReader reader = new CardReader();
 * Cancellable c = reader.read(tag, new Callback<Card>() {
 *     public void onSuccess(Card card) { ... }
 *     public void onError(ReadException e) { ... }
 * });
 * // onDestroy: c.cancel();
 * }</pre>
 */
public final class CardReader {
	private static final int TIMEOUT_MS = 5000;

	private final Executor work;
	private final Executor callbacks;

	/** Reads on a background thread shared by all card readers; callbacks on the main thread. */
	public CardReader() {
		this(Defaults.WORK, Defaults.MAIN);
	}

	public CardReader(Executor work, Executor callbacks) {
		if (work == null || callbacks == null) {
			throw new IllegalArgumentException("executors must be non-null");
		}
		this.work = work;
		this.callbacks = callbacks;
	}

	/** Blocking read. Call from a background thread. */
	@WorkerThread
	public Card read(Tag tag) throws ReadException {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		return read(tag, IsoDep.get(tag));
	}

	/**
	 * Reads on the work executor and reports once to {@code callback} on the
	 * callback executor, unless cancelled.
	 */
	public Cancellable read(Tag tag, Callback<Card> callback) {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		if (callback == null) throw new IllegalArgumentException("callback is null");
		// One IsoDep instance for both the read and cancel(), so cancel closes
		// the connection the read is actually using.
		final IsoDep isoDep = IsoDep.get(tag);
		return Call.start(work, callbacks, callback, isoDep, () -> read(tag, isoDep));
	}

	private static Card read(Tag tag, IsoDep isoDep) throws ReadException {
		if (isoDep == null) {
			throw new ReadException(ReadException.Reason.UNSUPPORTED, "card is not ISO-DEP");
		}
		try {
			isoDep.connect();
			isoDep.setTimeout(TIMEOUT_MS);
			return readWith(Bytes.hex(tag.getId()), isoDep::transceive);
		} catch (IOException e) {
			throw new ReadException(ReadException.Reason.CARD_LOST, "card lost: " + e.getMessage(), e);
		} finally {
			try {
				isoDep.close();
			} catch (IOException ignored) {
				// Already gone.
			}
		}
	}

	/** The read itself, over any transceiver. Package-private: the test seam. */
	static Card readWith(String uid, Transceiver transceiver) throws ReadException {
		try {
			final List<CardApp> apps = Emv.read(new Apdu(transceiver));
			if (apps == null) {
				throw new ReadException(ReadException.Reason.UNSUPPORTED,
						"no PPSE: not a contactless payment card");
			}
			return new Card(uid, apps);
		} catch (IOException e) {
			throw new ReadException(ReadException.Reason.CARD_LOST, "card lost: " + e.getMessage(), e);
		} catch (RuntimeException e) {
			throw new ReadException(ReadException.Reason.FAILED, e.toString(), e);
		}
	}

	/** Loaded on first use of the no-arg constructor only, so unit tests never touch Looper. */
	private static final class Defaults {
		static final Executor WORK = Executors.newSingleThreadExecutor(r -> {
			final Thread t = new Thread(r, "read3r-card");
			t.setDaemon(true);
			return t;
		});
		static final Executor MAIN = new Handler(Looper.getMainLooper())::post;
	}
}
