/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.common.mrz.IdCard;
import com.adkhambek.reader.nfc.common.NfcManager;
import com.adkhambek.reader.nfc.id.IdReaderListener;
import com.adkhambek.reader.nfc.id.IdReaderManager;
import com.adkhambek.reader.nfc.id.MrzKey;

public final class IdReaderActivity extends Activity implements IdReaderListener {

	private TextView output;
	private ImageView photo;
	private NfcManager nfc;
	private MrzKey key;
	private boolean destroyed;

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
		photo.setVisibility(android.view.View.GONE);
		root.addView(photo);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setText(R.string.id_prompt);
		root.addView(output);

		final ScrollView scroll = new ScrollView(this);
		scroll.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scroll.addView(root);
		setContentView(scroll);

		// Created before the missing-key bail-out below: onResume/onPause run
		// either way, and NfcManager no-ops when the device has no NFC adapter.
		nfc = new NfcManager(this);

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
		key = new MrzKey(doc, dob, exp);
	}

	@Override
	protected void onResume() {
		super.onResume();
		nfc.onResume(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		nfc.onPause(this);
	}

	@Override
	protected void onDestroy() {
		destroyed = true;
		recyclePhoto();
		output = null;
		photo = null;
		super.onDestroy();
	}

	private void recyclePhoto() {
		if (photo == null) return;
		final Drawable d = photo.getDrawable();
		photo.setImageDrawable(null);
		if (d instanceof BitmapDrawable) {
			final Bitmap b = ((BitmapDrawable) d).getBitmap();
			if (b != null && !b.isRecycled()) b.recycle();
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		final Tag tag = NfcManager.tagFrom(intent);
		if (tag != null && key != null) IdReaderManager.readCard(tag, key, this);
	}

	@Override
	public void onReading() {
		if (destroyed || output == null) return;
		output.setText(R.string.id_reading);
	}

	@Override
	public void onResult(Result<IdCard, Throwable> result) {
		if (destroyed || output == null) return;
		if (result.isErr()) {
			output.setText(getString(R.string.error_prefix, result.error().getMessage()));
			return;
		}
		render(result.value());
	}

	private void render(IdCard c) {
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

		if (c.fullName() != null) {
			sb.append("\n-- DG11 --\n");
			line(sb, "Full name", c.fullName());
			line(sb, "Other names", c.otherNames());
			line(sb, "Place of birth", c.placeOfBirth());
			line(sb, "Full DOB", c.fullDateOfBirth());
			line(sb, "Address", c.address());
			line(sb, "Telephone", c.telephone());
			line(sb, "Profession", c.profession());
			line(sb, "Title", c.title());
		}
		if (c.issuingAuthority() != null || c.dateOfIssue() != null) {
			sb.append("\n-- DG12 --\n");
			line(sb, "Issuing authority", c.issuingAuthority());
			line(sb, "Date of issue", c.dateOfIssue());
			line(sb, "Endorsements", c.endorsements());
		}
		if (!c.nationalData().isEmpty()) {
			sb.append("\n-- DG13 --\n");
			for (final Map.Entry<String, String> e : c.nationalData().entrySet()) {
				line(sb, e.getKey(), e.getValue());
			}
		}
		if (!c.presentDataGroups().isEmpty()) {
			sb.append("\nPresent: ");
			for (final String n : c.presentDataGroups()) sb.append(n).append(' ');
			sb.append('\n');
		}
		output.setText(sb.toString());

		if (c.photoBytes() != null) {
			final Bitmap bmp = decodePhoto(c.photoBytes());
			if (bmp != null) {
				final Drawable old = photo.getDrawable();
				photo.setImageBitmap(bmp);
				photo.setVisibility(android.view.View.VISIBLE);
				if (old instanceof BitmapDrawable) {
					final Bitmap oldBmp = ((BitmapDrawable) old).getBitmap();
					if (oldBmp != null && oldBmp != bmp && !oldBmp.isRecycled()) {
						oldBmp.recycle();
					}
				}
			} else {
				// BitmapFactory can't decode JPEG2000 (the common eMRTD face-image
				// codec), so the photo would otherwise vanish with no explanation.
				output.append("\n" + getString(R.string.id_photo_not_displayable, c.photoFormat()) + "\n");
			}
		}
	}

	private static Bitmap decodePhoto(byte[] bytes) {
		// Probe the dimensions first, then downsample to roughly the 500x600
		// ImageView. Decoding the full-resolution face image on the main thread
		// (onResult is posted to the main looper) janks the UI and risks OOM.
		final BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 500, 600);
		try {
			return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
		} catch (OutOfMemoryError oom) {
			opts.inSampleSize *= 2;
			try {
				return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
			} catch (OutOfMemoryError giveUp) {
				return null;
			}
		}
	}

	/** Largest power-of-two subsample that keeps the image at or above the target box. */
	private static int sampleSize(int width, int height, int reqWidth, int reqHeight) {
		int sample = 1;
		while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
			sample *= 2;
		}
		return sample;
	}

	private static void line(StringBuilder sb, String label, String value) {
		if (value == null || value.isEmpty()) return;
		sb.append(label);
		for (int i = label.length(); i < 18; ++i) sb.append(' ');
		sb.append(": ").append(value).append('\n');
	}
}
