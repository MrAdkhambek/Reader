/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.adkhambek.reader.common.mrz.IdCard;
import com.adkhambek.reader.qr.id.QrIdResult;
import com.adkhambek.reader.qr.id.QrIdScannerView;

public final class QrScannerActivity extends ComponentActivity {

	private QrIdScannerView qr;
	private TextView output;

	private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(),
			granted -> {
				if (granted) qr.start(this);
				else {
					output.setText(R.string.qr_permission_needed);
					Toast.makeText(this, R.string.qr_permission_needed, Toast.LENGTH_LONG).show();
				}
			});

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		qr = new QrIdScannerView(this);
		qr.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		qr.setLifecycleOwner(this);
		qr.setListener(this::renderResult);
		root.addView(qr);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setPadding(32, 32, 32, 32);
		output.setText(R.string.qr_prompt);
		final ScrollView outputScroll = new ScrollView(this);
		outputScroll.setLayoutParams(new LinearLayout.LayoutParams(
				MATCH_PARENT, 0, 1f));
		outputScroll.addView(output);
		root.addView(outputScroll);

		setContentView(root);

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			permissionLauncher.launch(Manifest.permission.CAMERA);
		}
		// On grant the lifecycle observer takes over; we don't need to start manually.
	}

	private void renderResult(QrIdResult result) {
		if (result.isMrz()) {
			output.setText(renderIdCard(result.mrz));
		} else {
			output.setText(getString(R.string.qr_raw_prefix, result.text));
		}
	}

	private static String renderIdCard(IdCard c) {
		final StringBuilder sb = new StringBuilder();
		line(sb, "Document type", c.documentType());
		line(sb, "Issuing country", c.issuingCountry());
		line(sb, "Document number", c.documentNumber());
		line(sb, "Last name", c.lastName());
		line(sb, "First name", c.firstName());
		line(sb, "Sex", c.sex());
		line(sb, "Nationality", c.nationality());
		line(sb, "Date of birth", c.dateOfBirth());
		line(sb, "Date of expiry", c.dateOfExpiry());
		line(sb, "Personal number", c.personalNumber());
		line(sb, "Optional data", c.optionalData());
		return sb.toString();
	}

	private static void line(StringBuilder sb, String label, String value) {
		if (value == null || value.isEmpty()) return;
		sb.append(label);
		for (int i = label.length(); i < 18; ++i) sb.append(' ');
		sb.append(": ").append(value).append('\n');
	}
}
