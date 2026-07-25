/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

/** The card responded, but carries no application this reader understands. */
public final class UnsupportedDocumentException extends ReadException {
	public UnsupportedDocumentException(String message) {
		super(message);
	}
}
