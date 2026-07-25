/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.id;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import com.adkhambek.reader.common.mrz.IdCard;
import com.adkhambek.reader.common.mrz.Mrz;
import com.adkhambek.reader.qr.view.QrScannerView;

/**
 * Drop-in QR scanner specialised for ID documents. Wraps {@link QrScannerView}
 * and, on each successful scan, runs the text through the ICAO 9303 MRZ decoder
 * before forwarding to the caller.
 *
 * <p>Usage mirrors {@link QrScannerView}: construct, call
 * {@link #setLifecycleOwner}, then {@link #setListener}.
 */
public final class QrIdScannerView extends FrameLayout {

	/** Callback for a successful scan. {@link QrIdResult#mrz} is set iff the
	 *  scanned text parsed as an MRZ. */
	public interface Listener {
		void onScanned(@NonNull QrIdResult result);

		default void onError(@NonNull Throwable t) {
		}
	}

	private final QrScannerView inner;
	private Listener listener;

	public QrIdScannerView(@NonNull Context ctx) {
		this(ctx, null);
	}

	public QrIdScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		this(ctx, attrs, 0);
	}

	public QrIdScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(ctx, attrs, defStyleAttr);
		inner = new QrScannerView(ctx);
		inner.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(inner);
		inner.setListener(new QrScannerView.Listener() {
			@Override
			public void onScanned(@NonNull com.adkhambek.reader.qr.common.ScanResult result) {
				deliver(result.text());
			}

			@Override
			public void onError(@NonNull Throwable t) {
				if (listener != null) listener.onError(t);
			}
		});
	}

	/** Forwarded to the inner {@link QrScannerView}. */
	public void setLifecycleOwner(@NonNull LifecycleOwner owner) {
		inner.setLifecycleOwner(owner);
	}

	public void setListener(@Nullable Listener l) {
		this.listener = l;
	}

	/** Start the camera now. See {@link QrScannerView#start}. */
	public void start(@NonNull LifecycleOwner owner) {
		inner.start(owner);
	}

	/** Stop the camera without tearing down. Mirrors {@link QrScannerView#stop()}. */
	public void stop() {
		inner.stop();
	}

	/** Re-arm the scanner after a successful scan. */
	public void resumeScanning() {
		inner.resumeScanning();
	}

	@NonNull public QrScannerView getQrScannerView() {
		return inner;
	}

	private void deliver(@NonNull String text) {
		if (listener == null) return;
		final String stripped = stripWhitespace(text);
		IdCard mrz = null;
		// Validate ICAO 9303 check digits before parsing — without this, any
		// length-90/88/72 payload would produce a populated IdCard (the parser
		// always writes those fields), so noise QR codes were being presented
		// as ID data.
		if (Mrz.isValidMrz(stripped)) {
			final IdCard candidate = new IdCard();
			Mrz.decodeMrz(stripped, candidate);
			if (candidate.documentNumber != null) {
				mrz = candidate;
			}
		}
		listener.onScanned(new QrIdResult(text, mrz));
	}

	private static String stripWhitespace(String s) {
		final StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c != '\n' && c != '\r' && c != ' ' && c != '\t') sb.append(c);
		}
		return sb.toString();
	}
}
