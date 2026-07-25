/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An {@link ApduChannel} that replays a scripted exchange.
 *
 * <p>Build the script with alternating {@link #expect}/{@link #reply} calls,
 * run the reader against it, then call {@link #assertExhausted()}. An APDU that
 * does not match the next scripted request throws {@link AssertionError}, so a
 * reader that starts issuing an extra command fails loudly rather than
 * silently reading past the end of the script.
 *
 * <p>Transcripts captured from real cards are worth far more than invented
 * ones — an invented transcript encodes the implementation's assumptions
 * rather than the card's behaviour. Mark synthetic fixtures as such.
 */
public final class ReplayChannel implements ApduChannel {

	private static final class Exchange {
		final byte[] request;
		final byte[] response;

		Exchange(byte[] request, byte[] response) {
			this.request = request;
			this.response = response;
		}
	}

	private final Deque<Exchange> script = new ArrayDeque<>();
	private String pendingRequest;
	private boolean connected;
	private boolean closed;

	public ReplayChannel expect(String requestHex) {
		if (pendingRequest != null) {
			throw new IllegalStateException("expect() called twice without reply()");
		}
		pendingRequest = requestHex;
		return this;
	}

	public ReplayChannel reply(String responseHex) {
		if (pendingRequest == null) {
			throw new IllegalStateException("reply() called without expect()");
		}
		script.addLast(new Exchange(Hex.decode(pendingRequest), Hex.decode(responseHex)));
		pendingRequest = null;
		return this;
	}

	@Override
	public void connect() {
		connected = true;
	}

	@Override
	public byte[] transceive(byte[] apdu) throws IOException {
		if (closed) throw new IOException("channel closed");
		final Exchange next = script.pollFirst();
		if (next == null) {
			throw new AssertionError("unexpected APDU, script exhausted: " + Hex.encode(apdu));
		}
		if (!java.util.Arrays.equals(next.request, apdu)) {
			throw new AssertionError("unexpected APDU"
					+ "\n  expected: " + Hex.encode(next.request)
					+ "\n  actual:   " + Hex.encode(apdu));
		}
		return next.response;
	}

	@Override
	public void setTimeout(int millis) {
		// no-op
	}

	@Override
	public void close() {
		closed = true;
	}

	/** True once {@link #connect()} has been called. */
	public boolean isConnected() {
		return connected;
	}

	/** Fails if any scripted exchange was not consumed. */
	public void assertExhausted() {
		if (!script.isEmpty()) {
			throw new AssertionError(script.size() + " scripted exchange(s) unused, next: "
					+ Hex.encode(script.peekFirst().request));
		}
	}
}
