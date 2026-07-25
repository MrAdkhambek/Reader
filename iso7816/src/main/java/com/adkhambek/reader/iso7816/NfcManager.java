/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import static android.nfc.NfcAdapter.EXTRA_TAG;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;

public final class NfcManager {
	private final NfcAdapter nfcAdapter;

	private static final String[][] TECHLISTS = {{IsoDep.class.getName()}};
	private static final IntentFilter[] TAGFILTERS = {new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)};

	public NfcManager(Activity activity) {
		nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
	}

	public void onPause(final Activity activity) {
		if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(activity);
	}

	public void onResume(final Activity activity) {
		if (nfcAdapter == null) return;
		// Must be mutable: the NFC framework fills in EXTRA_TAG before delivering.
		final PendingIntent pi = PendingIntent.getActivity(activity, 0,
				new Intent(activity, activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		nfcAdapter.enableForegroundDispatch(activity, pi, TAGFILTERS, TECHLISTS);
	}

	public static Tag tagFrom(Intent intent) {
		if (intent == null) return null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return intent.getParcelableExtra(EXTRA_TAG, Tag.class);
		}
		@SuppressWarnings("deprecation")
		final Tag legacy = (Tag) intent.getParcelableExtra(EXTRA_TAG);
		return legacy;
	}
}
