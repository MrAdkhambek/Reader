/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Reads ICAO 9303 ePassports and eMRTD ID cards over NFC: SELECT the eMRTD
 * applet, Basic Access Control, then EF.COM, DG1, and the optional DG11, DG12,
 * DG13 and DG2 through Secure Messaging.
 *
 * <pre>{@code
 * PassportReader reader = new PassportReader();
 * MrzKey key = new MrzKey("L898902C3", "690806", "940623");
 * Cancellable c = reader.read(tag, key, new Callback<Passport>() {
 *     public void onSuccess(Passport p) { ... }
 *     public void onError(ReadException e) { ... }
 * });
 * // onDestroy: c.cancel();
 * }</pre>
 *
 * <p>An optional data group that is absent or cannot be read is left null; it
 * does not fail the read.
 */
public final class PassportReader {
	private static final int TIMEOUT_MS = 15000;
	private static final byte[] SELECT_EMRTD = {
			0x00, (byte) 0xA4, 0x04, 0x0C, 0x07, (byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01};

	static final int FID_COM = 0x011E;
	static final int FID_DG1 = 0x0101;
	static final int FID_DG2 = 0x0102;
	static final int FID_DG11 = 0x010B;
	static final int FID_DG12 = 0x010C;
	static final int FID_DG13 = 0x010D;

	private final Executor work;
	private final Executor callbacks;

	/** Reads on a background thread shared by all passport readers; callbacks on the main thread. */
	public PassportReader() {
		this(Defaults.WORK, Defaults.MAIN);
	}

	public PassportReader(Executor work, Executor callbacks) {
		if (work == null || callbacks == null) {
			throw new IllegalArgumentException("executors must be non-null");
		}
		this.work = work;
		this.callbacks = callbacks;
	}

	/** Blocking read. Call from a background thread. */
	@WorkerThread
	public Passport read(Tag tag, MrzKey key) throws ReadException {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		if (key == null) throw new IllegalArgumentException("key is null");
		return read(IsoDep.get(tag), key);
	}

	/**
	 * Reads on the work executor and reports once to {@code callback} on the
	 * callback executor, unless cancelled.
	 */
	public Cancellable read(Tag tag, MrzKey key, Callback<Passport> callback) {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		if (key == null) throw new IllegalArgumentException("key is null");
		if (callback == null) throw new IllegalArgumentException("callback is null");
		// One IsoDep instance for both the read and cancel(), so cancel closes
		// the connection the read is actually using.
		final IsoDep isoDep = IsoDep.get(tag);
		return Call.start(work, callbacks, callback, isoDep, () -> read(isoDep, key));
	}

	private static Passport read(IsoDep isoDep, MrzKey key) throws ReadException {
		if (isoDep == null) {
			throw new ReadException(ReadException.Reason.UNSUPPORTED, "card is not ISO-DEP");
		}
		try {
			isoDep.connect();
			isoDep.setTimeout(TIMEOUT_MS);
			return readWith(isoDep::transceive, key);
		} catch (IOException e) {
			throw new ReadException(ReadException.Reason.CARD_LOST, "card lost: " + e.getMessage(), e);
		} finally {
			try {
				isoDep.close();
			} catch (IOException ignored) {
				// Already gone.
			}
		}
	}

	/** The read itself, over any transceiver. Package-private: the test seam. */
	static Passport readWith(Transceiver transceiver, MrzKey key) throws ReadException {
		if (key == null) throw new IllegalArgumentException("key is null");
		try {
			final Apdu apdu = new Apdu(transceiver);
			final byte[] selected = apdu.send(SELECT_EMRTD);
			if (!Apdu.ok(selected)) {
				throw new ReadException(ReadException.Reason.UNSUPPORTED,
						String.format(Locale.ROOT, "no eMRTD applet, SW=%04X", Apdu.sw(selected)));
			}
			final Bac bac;
			try {
				bac = Bac.mutualAuthenticate(apdu,
						Bac.mrzInfo(key.documentNumber, key.dateOfBirth, key.dateOfExpiry));
			} catch (Bac.BacException e) {
				throw new ReadException(ReadException.Reason.AUTH_FAILED, e.getMessage(), e);
			}
			final SecureMessaging sm = new SecureMessaging(bac);
			final EfReader files = new EfReader(plain -> sm.transceive(apdu, plain));
			final Passport.Builder out = new Passport.Builder();

			final byte[] com = files.read(FID_COM);
			if (com != null) out.presentDataGroups = DgParser.parseCom(com);
			final byte[] dg1 = files.read(FID_DG1);
			if (dg1 != null) DgParser.parseDg1(dg1, out);

			for (final int fid : new int[]{FID_DG11, FID_DG12, FID_DG13, FID_DG2}) {
				try {
					readOptional(files, fid, out);
				} catch (IOException lost) {
					break; // card gone: keep what was read, skip the rest
				}
			}
			return out.build();
		} catch (IOException e) {
			throw new ReadException(ReadException.Reason.CARD_LOST, "card lost: " + e.getMessage(), e);
		} catch (RuntimeException e) {
			throw new ReadException(ReadException.Reason.FAILED, e.toString(), e);
		}
	}

	/** An optional group that fails to read or parse is left null. */
	private static void readOptional(EfReader files, int fid, Passport.Builder out) throws IOException {
		final byte[] ef;
		try {
			ef = files.read(fid);
		} catch (RuntimeException unreadable) {
			return;
		}
		if (ef == null) return;
		try {
			switch (fid) {
				case FID_DG11: DgParser.parseDg11(ef, out); break;
				case FID_DG12: DgParser.parseDg12(ef, out); break;
				case FID_DG13: DgParser.parseDg13(ef, out); break;
				default: {
					final Dg2.Image image = Dg2.extract(ef);
					if (image.data != null) out.photo = new Photo(image.data, image.format);
				}
			}
		} catch (RuntimeException malformed) {
			// Leave this group null.
		}
	}

	/** Loaded on first use of the no-arg constructor only, so unit tests never touch Looper. */
	private static final class Defaults {
		static final Executor WORK = Executors.newSingleThreadExecutor(r -> {
			final Thread t = new Thread(r, "read3r-passport");
			t.setDaemon(true);
			return t;
		});
		static final Executor MAIN = new Handler(Looper.getMainLooper())::post;
	}
}
