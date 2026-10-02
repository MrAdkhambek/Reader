/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adkhambek.reader.passport.Callback;
import com.adkhambek.reader.passport.Cancellable;
import com.adkhambek.reader.passport.DocumentDetails;
import com.adkhambek.reader.passport.Passport;
import com.adkhambek.reader.passport.PassportReader;
import com.adkhambek.reader.passport.PersonalDetails;
import com.adkhambek.reader.passport.Photo;
import com.adkhambek.reader.passport.ReadException;
import com.adkhambek.reader.passport.mrz.MrzDocument;
import com.adkhambek.reader.passport.mrz.MrzKey;

import java.util.Map;

public final class IdReaderActivity extends Activity implements NfcAdapter.ReaderCallback {

	private final PassportReader reader = new PassportReader();
	private TextView output;
	private ImageView photo;
	private MrzKey key;
	private Cancellable pending;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(40, 40, 40, 40);

		photo = new ImageView(this);
		final LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(500, 600);
		ip.gravity = Gravity.CENTER_HORIZONTAL;
		photo.setLayoutParams(ip);
		photo.setVisibility(View.GONE);
		root.addView(photo);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setText(R.string.id_prompt);
		root.addView(output);

		final ScrollView scroll = new ScrollView(this);
		scroll.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		scroll.addView(root);
		setContentView(scroll);

		String doc = getIntent().getStringExtra(IdInputActivity.K_DOC);
		String dob = getIntent().getStringExtra(IdInputActivity.K_DOB);
		String exp = getIntent().getStringExtra(IdInputActivity.K_EXP);
		if (doc == null || dob == null || exp == null) {
			final SharedPreferences prefs = getSharedPreferences(IdInputActivity.PREFS, 0);
			if (doc == null) doc = prefs.getString(IdInputActivity.K_DOC, null);
			if (dob == null) dob = prefs.getString(IdInputActivity.K_DOB, null);
			if (exp == null) exp = prefs.getString(IdInputActivity.K_EXP, null);
		}
		if (doc == null || dob == null || exp == null) {
			output.setText(R.string.id_missing_key);
			return;
		}
		try {
			key = new MrzKey(doc, dob, exp);
		} catch (IllegalArgumentException e) {
			output.setText(getString(R.string.error_prefix, e.getMessage()));
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (key != null) Nfc.enable(this, this);
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
		recyclePhoto();
		super.onDestroy();
	}

	/** Binder thread. Hop to the main thread; the reader does its own threading from there. */
	@Override
	public void onTagDiscovered(Tag tag) {
		runOnUiThread(() -> {
			if (pending != null) pending.cancel();
			recyclePhoto();
			photo.setVisibility(View.GONE);
			output.setText(R.string.id_reading);
			pending = reader.read(tag, key, new Callback<Passport>() {
				@Override
				public void onSuccess(Passport passport) {
					render(passport);
				}

				@Override
				public void onError(ReadException e) {
					output.setText(getString(R.string.error_prefix, e.reason() + ": " + e.getMessage()));
				}
			});
		});
	}

	private void render(Passport p) {
		final StringBuilder sb = new StringBuilder();
		final MrzDocument m = p.mrz();
		QrScannerActivity.line(sb, "Document type", m.documentType());
		QrScannerActivity.line(sb, "Issuing country", m.issuingCountry());
		QrScannerActivity.line(sb, "Document number", m.documentNumber());
		QrScannerActivity.line(sb, "Last name", m.lastName());
		QrScannerActivity.line(sb, "First name", m.firstName());
		QrScannerActivity.line(sb, "Sex", m.sex());
		QrScannerActivity.line(sb, "Nationality", m.nationality());
		QrScannerActivity.line(sb, "Date of birth", m.dateOfBirth());
		QrScannerActivity.line(sb, "Date of expiry", m.dateOfExpiry());
		QrScannerActivity.line(sb, "Personal number", m.personalNumber());

		final PersonalDetails d = p.personalDetails();
		if (d != null) {
			sb.append("\n-- DG11 --\n");
			QrScannerActivity.line(sb, "Full name", d.fullName());
			QrScannerActivity.line(sb, "Other names", d.otherNames());
			QrScannerActivity.line(sb, "Place of birth", d.placeOfBirth());
			QrScannerActivity.line(sb, "Full DOB", d.fullDateOfBirth());
			QrScannerActivity.line(sb, "Address", d.address());
			QrScannerActivity.line(sb, "Telephone", d.telephone());
			QrScannerActivity.line(sb, "Profession", d.profession());
			QrScannerActivity.line(sb, "Title", d.title());
		}
		final DocumentDetails dd = p.documentDetails();
		if (dd != null) {
			sb.append("\n-- DG12 --\n");
			QrScannerActivity.line(sb, "Issuing authority", dd.issuingAuthority());
			QrScannerActivity.line(sb, "Date of issue", dd.dateOfIssue());
			QrScannerActivity.line(sb, "Endorsements", dd.endorsements());
		}
		if (!p.nationalData().isEmpty()) {
			sb.append("\n-- DG13 --\n");
			for (final Map.Entry<String, String> e : p.nationalData().entrySet()) {
				QrScannerActivity.line(sb, e.getKey(), e.getValue());
			}
		}
		if (!p.presentDataGroups().isEmpty()) {
			// TextUtils.join, not String.join: the latter needs API 26 and minSdk is 21.
			sb.append("\nPresent: ").append(TextUtils.join(" ", p.presentDataGroups())).append('\n');
		}
		output.setText(sb.toString());

		final Photo ph = p.photo();
		if (ph == null) return;
		final Bitmap bmp = decodePhoto(ph.bytes());
		if (bmp == null) {
			// BitmapFactory cannot decode JPEG 2000, the common eMRTD face codec.
			output.append("\n" + getString(R.string.id_photo_not_displayable, ph.format()) + "\n");
			return;
		}
		recyclePhoto();
		photo.setImageBitmap(bmp);
		photo.setVisibility(View.VISIBLE);
	}

	private void recyclePhoto() {
		final Drawable d = photo.getDrawable();
		photo.setImageDrawable(null);
		if (d instanceof BitmapDrawable) {
			final Bitmap b = ((BitmapDrawable) d).getBitmap();
			if (b != null && !b.isRecycled()) b.recycle();
		}
	}

	/** Downsampled to roughly the 500×600 view: decoding the full image on the main thread janks and risks OOM. */
	private static Bitmap decodePhoto(byte[] bytes) {
		final BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = 1;
		while (bounds.outWidth / (opts.inSampleSize * 2) >= 500
				&& bounds.outHeight / (opts.inSampleSize * 2) >= 600) {
			opts.inSampleSize *= 2;
		}
		try {
			return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
		} catch (OutOfMemoryError oom) {
			return null;
		}
	}
}
