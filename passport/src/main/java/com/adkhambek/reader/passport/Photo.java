/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The DG2 face image. {@code bytes} is not copied — treat it as read-only.
 * Biometric data: do not log or persist it casually.
 */
public record Photo(byte[] bytes, PhotoFormat format) {
}
