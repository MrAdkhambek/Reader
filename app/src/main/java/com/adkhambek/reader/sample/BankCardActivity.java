/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.app.Activity;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adkhambek.reader.card.Callback;
import com.adkhambek.reader.card.Cancellable;
import com.adkhambek.reader.card.Card;
import com.adkhambek.reader.card.CardApp;
import com.adkhambek.reader.card.CardReader;
import com.adkhambek.reader.card.ReadException;

public final class BankCardActivity extends Activity implements NfcAdapter.ReaderCallback {

	private final CardReader reader = new CardReader();
	private TextView textView;
	private Cancellable pending;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		textView = new TextView(this);
		textView.setTextSize(14);
		textView.setPadding(40, 40, 40, 40);
		textView.setTypeface(Typeface.MONOSPACE);
		textView.setMovementMethod(ScrollingMovementMethod.getInstance());
		textView.setText(R.string.bank_prompt);

		final ScrollView scroll = new ScrollView(this);
		scroll.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		scroll.addView(textView);
		setContentView(scroll);
	}

	@Override
	protected void onResume() {
		super.onResume();
		Nfc.enable(this, this);
	}

	@Override
	protected void onPause() {
		Nfc.disable(this);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// On the main thread, so the callback cannot fire after this.
		if (pending != null) pending.cancel();
		super.onDestroy();
	}

	/** Binder thread. Hop to the main thread; the reader does its own threading from there. */
	@Override
	public void onTagDiscovered(Tag tag) {
		runOnUiThread(() -> {
			if (pending != null) pending.cancel();
			textView.setText(R.string.reading);
			pending = reader.read(tag, new Callback<Card>() {
				@Override
				public void onSuccess(Card card) {
					textView.setText(card.isUnknown() ? getString(R.string.unknown_card) : format(card));
				}

				@Override
				public void onError(ReadException e) {
					textView.setText(getString(R.string.error_prefix, e.reason() + ": " + e.getMessage()));
				}
			});
		});
	}

	private static String format(Card card) {
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final CardApp a : card.apps()) {
			if (!first) sb.append('\n').append("--------\n\n");
			first = false;

			if (a.label() != null && !a.label().isEmpty()) {
				sb.append(a.label()).append('\n');
				for (int i = 0; i < a.label().length(); ++i) sb.append('=');
				sb.append('\n');
			}
			line(sb, "PAN", a.pan());
			line(sb, "Serial", a.panSequence());
			line(sb, "Holder", join(" · ", a.cardholder(), a.country()));
			line(sb, "Version", a.appVersion());
			line(sb, "Validity", join(" - ", a.effectiveDate(), a.expiryDate()));
			if (a.currency() != null) line(sb, "Currency", a.currency().name());
		}
		return sb.toString();
	}

	private static void line(StringBuilder sb, String label, String value) {
		if (value != null && !value.isEmpty()) sb.append(label).append(": ").append(value).append('\n');
	}

	private static String join(String sep, String a, String b) {
		final boolean ha = a != null && !a.isEmpty();
		final boolean hb = b != null && !b.isEmpty();
		if (ha && hb) return a + sep + b;
		if (ha) return a;
		return hb ? b : null;
	}
}
