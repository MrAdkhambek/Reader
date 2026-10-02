/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card.iso7816;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * A {@link Transceiver} that replays a scripted exchange. An APDU that does
 * not match the next scripted request throws {@link AssertionError}, so a
 * reader that changes what it sends fails loudly.
 *
 * <p>Every transcript in this test suite is SYNTHETIC: it encodes what the
 * implementation expects, not what a real card does.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public final class Replay implements Transceiver {

	private final Deque<byte[][]> script = new ArrayDeque<>();
	private byte[] pending;

	public static byte[] hex(String s) {
		final String c = s.replaceAll("\\s", "");
		final byte[] out = new byte[c.length() / 2];
		for (int i = 0; i < out.length; ++i) {
			out[i] = (byte) Integer.parseInt(c.substring(2 * i, 2 * i + 2), 16);
		}
		return out;
	}

	public Replay expect(String request) {
		if (pending != null) throw new IllegalStateException("expect() twice without reply()");
		pending = hex(request);
		return this;
	}

	public Replay reply(String response) {
		if (pending == null) throw new IllegalStateException("reply() without expect()");
		script.addLast(new byte[][]{pending, hex(response)});
		pending = null;
		return this;
	}

	@Override
	public byte[] transceive(byte[] apdu) {
		final byte[][] next = script.pollFirst();
		if (next == null) {
			throw new AssertionError("unexpected APDU, script exhausted: " + hexOf(apdu));
		}
		if (!Arrays.equals(next[0], apdu)) {
			throw new AssertionError("unexpected APDU\n  expected: " + hexOf(next[0])
					+ "\n  actual:   " + hexOf(apdu));
		}
		return next[1];
	}

	public void assertExhausted() {
		if (!script.isEmpty()) {
			throw new AssertionError(script.size() + " scripted exchange(s) unused, next: "
					+ hexOf(script.peekFirst()[0]));
		}
	}

	private static String hexOf(byte[] b) {
		final StringBuilder sb = new StringBuilder(b.length * 2);
		for (final byte x : b) sb.append(String.format("%02X", x));
		return sb.toString();
	}
}
