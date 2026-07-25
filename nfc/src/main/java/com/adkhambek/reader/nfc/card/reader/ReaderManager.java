/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.card.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.adkhambek.reader.nfc.card.bean.Card;
import com.adkhambek.reader.nfc.card.bean.CardApp;
import com.adkhambek.reader.common.Hex;
import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.common.WorkerThread;

public final class ReaderManager {
	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(new ThreadFactory() {
		private final AtomicInteger n = new AtomicInteger();

		@Override
		public Thread newThread(Runnable r) {
			final Thread t = new Thread(r, "NFCard-reader-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});
	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	private ReaderManager() {
	}

	/**
	 * Synchronous read. Caller is responsible for invoking from a background thread
	 * (this method does NFC I/O which would block the main thread).
	 */
	@WorkerThread
	public static Result<Card, Exception> readCard(final Tag tag) {
		return doRead(tag);
	}

	public static void readCard(final Tag tag, ReaderListener listener) {
		final WeakReference<ReaderListener> weak = new WeakReference<>(listener);
		MAIN.post(() -> {
			final ReaderListener l = weak.get();
			if (l != null) l.onReading();
		});

		EXEC.execute(() -> {
			Result<Card, Exception> result;
			try {
				result = doRead(tag);
			} catch (Throwable t) {
				Log.e("NFCard", "doRead threw", t);
				result = Result.err(t instanceof Exception
						? (Exception) t
						: new RuntimeException(t));
			}
			final Result<Card, Exception> finalResult = result;
			MAIN.post(() -> {
				final ReaderListener l = weak.get();
				if (l != null) l.onResult(finalResult);
			});
		});
	}

	private static Result<Card, Exception> doRead(Tag tag) {
		if (tag == null) {
			return Result.err(new IllegalArgumentException("tag is null"));
		}

		try {
			final String uid = Hex.encode(tag.getId());
			Log.i("NFCard", "readCard tag techCount=" + tag.getTechList().length);

			List<CardApp> apps = Collections.emptyList();
			final IsoDep isodep = IsoDep.get(tag);
			if (isodep != null) {
				isodep.connect();
				try {
					try {
						isodep.setTimeout(5000);
					} catch (Exception e) {
						Log.w("NFCard", "setTimeout failed", e);
					}
					apps = EMV.readCard(isodep);
				} finally {
					try {
						isodep.close();
					} catch (Exception e) {
						Log.w("NFCard", "isodep.close failed", e);
					}
				}
			}
			return Result.ok(new Card(uid, apps));
		} catch (Exception e) {
			Log.e("NFCard", "readCard failed", e);
			return Result.err(e);
		}
	}

}
