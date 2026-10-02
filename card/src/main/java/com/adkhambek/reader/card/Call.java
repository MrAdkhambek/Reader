/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One asynchronous read: runs a blocking job on the work executor and
 * delivers its outcome once on the callback executor, unless cancelled.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Call<T> implements Cancellable, Runnable {

	interface Job<T> {
		T run() throws ReadException;
	}

	private final Executor callbacks;
	private final Closeable connection;
	private final Job<T> job;
	// Non-null until delivered or cancelled; getAndSet(null) makes each happen once.
	private final AtomicReference<Callback<T>> callback;

	private Call(Executor callbacks, Callback<T> callback, Closeable connection, Job<T> job) {
		this.callbacks = callbacks;
		this.callback = new AtomicReference<>(callback);
		this.connection = connection;
		this.job = job;
	}

	/** @param connection closed by {@link #cancel()}; may be null */
	static <T> Call<T> start(Executor work, Executor callbacks, Callback<T> callback,
			Closeable connection, Job<T> job) {
		final Call<T> call = new Call<>(callbacks, callback, connection, job);
		work.execute(call);
		return call;
	}

	@Override
	public void run() {
		if (callback.get() == null) return; // cancelled before it started
		T value = null;
		ReadException error = null;
		try {
			value = job.run();
		} catch (ReadException e) {
			error = e;
		} catch (Throwable e) {
			// Includes Errors (e.g. StackOverflowError from hostile nested BER),
			// so the callback still fires exactly once.
			error = new ReadException(ReadException.Reason.FAILED, e.toString(), e);
		}
		final T v = value;
		final ReadException err = error;
		callbacks.execute(() -> {
			final Callback<T> cb = callback.getAndSet(null);
			if (cb == null) return; // cancelled while in flight
			if (err == null) cb.onSuccess(v);
			else cb.onError(err);
		});
	}

	@Override
	public void cancel() {
		if (callback.getAndSet(null) == null) return; // already delivered or cancelled
		if (connection == null) return;
		try {
			connection.close();
		} catch (IOException ignored) {
			// Closing is only to make the read fail fast; nothing to report.
		}
	}
}
