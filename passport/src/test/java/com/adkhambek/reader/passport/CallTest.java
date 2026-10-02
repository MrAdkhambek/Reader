/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class CallTest {

	private static final Executor DIRECT = Runnable::run;

	private static final class Recorder<T> implements Callback<T> {
		final List<Object> events = new ArrayList<>();

		@Override
		public void onSuccess(T value) {
			events.add(value);
		}

		@Override
		public void onError(ReadException error) {
			events.add(error);
		}
	}

	private static final class Connection implements Closeable {
		boolean closed;

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test public void deliversSuccessOnce() {
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> "ok");
		assertEquals(Collections.singletonList("ok"), cb.events);
	}

	@Test public void deliversReadExceptionAsIs() {
		final ReadException e = new ReadException(ReadException.Reason.CARD_LOST, "gone");
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> {
			throw e;
		});
		assertSame(e, cb.events.get(0));
	}

	@Test public void wrapsRuntimeExceptionAsFailed() {
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> {
			throw new IllegalStateException("boom");
		});
		final ReadException e = (ReadException) cb.events.get(0);
		assertEquals(ReadException.Reason.FAILED, e.reason());
		assertTrue(e.getCause() instanceof IllegalStateException);
	}

	@Test public void cancelBeforeRunSkipsTheJobAndClosesTheConnection() {
		final List<Runnable> queued = new ArrayList<>();
		final Connection connection = new Connection();
		final boolean[] ran = {false};
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(queued::add, DIRECT, cb, connection, () -> {
			ran[0] = true;
			return "ok";
		});
		call.cancel();
		queued.get(0).run();

		assertFalse(ran[0]);
		assertTrue(connection.closed);
		assertTrue(cb.events.isEmpty());
	}

	/** Review focus: the read finished and delivery is queued on the callback
	 *  thread, but cancel() gets there first. */
	@Test public void cancelAfterWorkButBeforeDeliverySuppressesTheCallback() {
		final List<Runnable> deliveries = new ArrayList<>();
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(DIRECT, deliveries::add, cb, null, () -> "ok");
		call.cancel();
		deliveries.get(0).run();

		assertTrue(cb.events.isEmpty());
	}

	@Test public void cancelAfterDeliveryIsANoOp() {
		final Connection connection = new Connection();
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(DIRECT, DIRECT, cb, connection, () -> "ok");
		call.cancel();
		call.cancel();

		assertEquals(1, cb.events.size());
		assertFalse(connection.closed);
	}
}
