/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker that the annotated method must be invoked on a worker (non-main) thread.
 * Same intent as {@code androidx.annotation.WorkerThread} without pulling the
 * androidx (and transitively Kotlin stdlib) dependency.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE, ElementType.PARAMETER})
public @interface WorkerThread {
}
