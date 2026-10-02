/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.adkhambek.reader.passport.Mrz;
import com.adkhambek.reader.passport.MrzDocument;
import com.adkhambek.reader.qr.QrCode;
import com.adkhambek.reader.qr.QrScannerView;

public final class QrScannerActivity extends ComponentActivity implements QrScannerView.Listener {

	private QrScannerView qr;
	private TextView output;

	private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(),
			granted -> {
				if (granted) {
					qr.start(this, this);
				} else {
					output.setText(R.string.qr_permission_needed);
					Toast.makeText(this, R.string.qr_permission_needed, Toast.LENGTH_LONG).show();
				}
			});

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		qr = new QrScannerView(this);
		qr.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		root.addView(qr);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setPadding(32, 32, 32, 32);
		output.setText(R.string.qr_prompt);
		final ScrollView outputScroll = new ScrollView(this);
		outputScroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		outputScroll.addView(output);
		root.addView(outputScroll);

		setContentView(root);

		qr.start(this, this);
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PERMISSION_GRANTED) {
			permissionLauncher.launch(Manifest.permission.CAMERA);
		}
	}

	@Override
	public void onScanned(@NonNull QrCode code) {
		// An ID card's QR may carry its MRZ. Check digits first: a random payload
		// of the right length would otherwise decode into plausible-looking fields.
		final String text = code.text();
		output.setText(Mrz.isValid(text)
				? render(Mrz.decode(text))
				: getString(R.string.qr_raw_prefix, text));
	}

	@Override
	public void onError(@NonNull Throwable t) {
		output.setText(getString(R.string.error_prefix, String.valueOf(t.getMessage())));
	}

	private static String render(MrzDocument d) {
		final StringBuilder sb = new StringBuilder();
		line(sb, "Document type", d.documentType());
		line(sb, "Issuing country", d.issuingCountry());
		line(sb, "Document number", d.documentNumber());
		line(sb, "Last name", d.lastName());
		line(sb, "First name", d.firstName());
		line(sb, "Sex", d.sex());
		line(sb, "Nationality", d.nationality());
		line(sb, "Date of birth", d.dateOfBirth());
		line(sb, "Date of expiry", d.dateOfExpiry());
		line(sb, "Personal number", d.personalNumber());
		line(sb, "Optional data", d.optionalData());
		return sb.toString();
	}

	static void line(StringBuilder sb, String label, String value) {
		if (value == null || value.isEmpty()) return;
		sb.append(label);
		for (int i = label.length(); i < 18; ++i) sb.append(' ');
		sb.append(": ").append(value).append('\n');
	}
}
