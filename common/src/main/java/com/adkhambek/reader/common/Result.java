/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common;

/**
 * Sum type holding either a value (Ok) or an error (Err).
 * Java analog of Rust's {@code Result<T, E>} / C++20 {@code std::expected<T, E>}.
 */
public final class Result<T, E> {
	private final T value;
	private final E error;
	private final boolean ok;

	private Result(T value, E error, boolean ok) {
		this.value = value;
		this.error = error;
		this.ok = ok;
	}

	public static <T, E> Result<T, E> ok(T value) {
		return new Result<>(value, null, true);
	}

	public static <T, E> Result<T, E> err(E error) {
		return new Result<>(null, error, false);
	}

	public boolean isOk() {
		return ok;
	}

	public boolean isErr() {
		return !ok;
	}

	/** @throws IllegalStateException if {@link #isErr()}. */
	public T value() {
		if (!ok) throw new IllegalStateException("Result is err: " + error);
		return value;
	}

	/** @throws IllegalStateException if {@link #isOk()}. */
	public E error() {
		if (ok) throw new IllegalStateException("Result is ok");
		return error;
	}

	public T valueOr(T fallback) {
		return ok ? value : fallback;
	}

	@Override
	public String toString() {
		return ok ? ("Ok(" + value + ")") : ("Err(" + error + ")");
	}
}
