/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.nfc.NfcAdapter;

/**
 * NFC reader mode for the two NFC screens: while enabled, tags go straight to
 * the callback (on a binder thread) instead of through intents.
 */
final class Nfc {
	private static final int FLAGS = NfcAdapter.FLAG_READER_NFC_A
			| NfcAdapter.FLAG_READER_NFC_B
			| NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

	private Nfc() {
	}

	/** Call from onResume. No-op on a device without NFC. */
	static void enable(Activity activity, NfcAdapter.ReaderCallback callback) {
		final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
		if (adapter != null) adapter.enableReaderMode(activity, callback, FLAGS, null);
	}

	/** Call from onPause. */
	static void disable(Activity activity) {
		final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
		if (adapter != null) adapter.disableReaderMode(activity);
	}
}
