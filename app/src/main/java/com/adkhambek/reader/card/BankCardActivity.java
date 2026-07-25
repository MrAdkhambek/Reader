/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.nfc.card.bean.Card;
import com.adkhambek.reader.nfc.card.bean.CardApp;
import com.adkhambek.reader.nfc.card.reader.ReaderListener;
import com.adkhambek.reader.nfc.card.reader.ReaderManager;
import com.adkhambek.reader.nfc.common.NfcManager;

public final class BankCardActivity extends Activity implements ReaderListener {

	private TextView textView;
	private NfcManager nfc;
	private boolean destroyed;

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
		scroll.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scroll.addView(textView);
		setContentView(scroll);

		nfc = new NfcManager(this);
		onNewIntent(getIntent());
	}

	@Override
	protected void onPause() {
		super.onPause();
		nfc.onPause(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		nfc.onResume(this);
	}

	@Override
	protected void onDestroy() {
		destroyed = true;
		textView = null;
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		final Tag tag = NfcManager.tagFrom(intent);
		if (tag != null) ReaderManager.readCard(tag, this);
	}

	@Override
	public void onReading() {
		if (destroyed || textView == null) return;
		textView.setText(R.string.reading);
	}

	@Override
	public void onResult(Result<Card, Exception> result) {
		if (destroyed || textView == null) return;
		if (result.isErr()) {
			textView.setText(getString(R.string.error_prefix, result.error().getMessage()));
			return;
		}
		final Card card = result.value();
		textView.setText(card.isUnknown() ? getString(R.string.unknown_card) : format(card));
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
			line(sb, "Holder", combineDot(a.cardholder(), a.country()));
			line(sb, "Version", a.appVersion());
			line(sb, "Validity", combineDate(a.effectiveDate(), a.expiryDate()));
			if (a.currency() != null) line(sb, "Currency", a.currency().name());
		}
		return sb.toString();
	}

	private static void line(StringBuilder sb, String label, String value) {
		if (value != null && !value.isEmpty())
			sb.append(label).append(": ").append(value).append('\n');
	}

	private static String combineDot(String a, String b) {
		final boolean ha = a != null && !a.isEmpty();
		final boolean hb = b != null && !b.isEmpty();
		if (ha && hb) return a + " · " + b;
		if (ha) return a;
		if (hb) return b;
		return null;
	}

	private static String combineDate(String effective, String expiry) {
		final boolean he = effective != null && !effective.isEmpty();
		final boolean hx = expiry != null && !expiry.isEmpty();
		if (he && hx) return effective + " - " + expiry;
		if (he) return effective;
		if (hx) return expiry;
		return null;
	}
}
