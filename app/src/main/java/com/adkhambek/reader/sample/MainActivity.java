/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(android.view.Gravity.CENTER);
		root.setPadding(64, 64, 64, 64);
		root.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		final TextView title = new TextView(this);
		title.setText(R.string.app_name);
		title.setTextSize(28);
		title.setGravity(android.view.Gravity.CENTER);
		title.setPadding(0, 0, 0, 80);
		root.addView(title);

		final Button bank = new Button(this);
		bank.setText(R.string.menu_bank);
		bank.setOnClickListener(v -> startActivity(new Intent(this, BankCardActivity.class)));
		root.addView(bank);

		final Button id = new Button(this);
		id.setText(R.string.menu_id);
		id.setOnClickListener(v -> startActivity(new Intent(this, IdInputActivity.class)));
		root.addView(id);

		final Button qr = new Button(this);
		qr.setText(R.string.menu_qr);
		qr.setOnClickListener(v -> startActivity(new Intent(this, QrScannerActivity.class)));
		root.addView(qr);

		setContentView(root);
	}
}
