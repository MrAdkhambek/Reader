/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;

import java.util.Locale;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Collects the three MRZ fields needed for BAC and hands off to {@link IdReaderActivity}. */
public final class IdInputActivity extends Activity {

	static final String PREFS = "nfcid.prefs";
	static final String K_DOC = "doc";
	static final String K_DOB = "dob";
	static final String K_EXP = "exp";

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(48, 48, 48, 48);
		final SharedPreferences prefs = getSharedPreferences(PREFS, 0);

		final EditText doc = new EditText(this);
		final EditText dob = new EditText(this);
		final EditText exp = new EditText(this);

		label(root, R.string.id_doc_label);
		doc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		doc.setHint(R.string.id_doc_hint);
		doc.setText(prefs.getString(K_DOC, ""));
		root.addView(doc);

		label(root, R.string.id_dob_label);
		dob.setInputType(InputType.TYPE_CLASS_NUMBER);
		dob.setHint(R.string.id_dob_hint);
		dob.setText(prefs.getString(K_DOB, ""));
		root.addView(dob);

		label(root, R.string.id_exp_label);
		exp.setInputType(InputType.TYPE_CLASS_NUMBER);
		exp.setHint(R.string.id_exp_hint);
		exp.setText(prefs.getString(K_EXP, ""));
		root.addView(exp);

		final Button start = new Button(this);
		start.setText(R.string.id_read);
		final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.topMargin = 64;
		start.setLayoutParams(lp);
		start.setOnClickListener(v -> {
			final String d = doc.getText().toString().trim().toUpperCase(Locale.ROOT);
			final String dobStr = dob.getText().toString().trim();
			final String expStr = exp.getText().toString().trim();
			if (d.isEmpty() || dobStr.length() != 6 || expStr.length() != 6) {
				Toast.makeText(this, R.string.id_fill_all, Toast.LENGTH_LONG).show();
				return;
			}
			prefs.edit().putString(K_DOC, d).putString(K_DOB, dobStr).putString(K_EXP, expStr).apply();
			final Intent i = new Intent(this, IdReaderActivity.class);
			i.putExtra(K_DOC, d);
			i.putExtra(K_DOB, dobStr);
			i.putExtra(K_EXP, expStr);
			startActivity(i);
		});
		root.addView(start);

		setContentView(root);
	}

	private void label(LinearLayout parent, int resId) {
		final TextView t = new TextView(this);
		t.setText(resId);
		t.setPadding(0, 24, 0, 0);
		parent.addView(t);
	}
}
