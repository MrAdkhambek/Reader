/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.adkhambek.reader.common.Hex;
import com.adkhambek.reader.common.Result;
import com.adkhambek.reader.common.WorkerThread;
import com.adkhambek.reader.common.tech.Iso7816;
import com.adkhambek.reader.nfc.id.bac.Bac;
import com.adkhambek.reader.nfc.id.bac.SecureMessaging;
import com.adkhambek.reader.common.mrz.IdCard;
import com.adkhambek.reader.nfc.id.dg.Dg2;
import com.adkhambek.reader.nfc.id.dg.DgParser;
import com.adkhambek.reader.common.mrz.Mrz;

/**
 * Async/sync entry points for reading an ICAO 9303 eMRTD (passport / national
 * ID with eMRTD applet). Performs SELECT eMRTD, BAC mutual auth, then reads
 * EF.COM + DG1 + DG2 + DG11/12/13 through Secure Messaging.
 */
public final class IdReaderManager {
	private static final String TAG = "NFCid";

	private static final byte[] AID_EMRTD = {(byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01};

	// Short File Identifier (last byte of) the EFs we care about. Selecting by FID
	// uses the full 2-byte file identifier inside MF/DF.
	private static final int FID_COM = 0x011E;
	private static final int FID_DG1 = 0x0101;
	private static final int FID_DG2 = 0x0102;
	private static final int FID_DG11 = 0x010B;
	private static final int FID_DG12 = 0x010C;
	private static final int FID_DG13 = 0x010D;

	private static final int MAX_CHUNK = 0xDF; // 223; keeps room for SM overhead within 256B Le.
	// Header bytes read up front to size the EF: tag(1) + up to 0x83 + 3 length
	// bytes covers files up to ~16 MB. (4 was too few for 3-byte BER lengths.)
	private static final int HEAD_LEN = 5;

	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(new ThreadFactory() {
		private final AtomicInteger n = new AtomicInteger();

		@Override
		public Thread newThread(Runnable r) {
			final Thread t = new Thread(r, "NFCid-reader-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});
	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	private IdReaderManager() {
	}

	@WorkerThread
	public static Result<IdCard, Exception> readCard(Tag tag, MrzKey key) {
		return doRead(tag, key);
	}

	public static void readCard(final Tag tag, final MrzKey key, IdReaderListener listener) {
		final WeakReference<IdReaderListener> weak = new WeakReference<>(listener);
		MAIN.post(() -> {
			final IdReaderListener l = weak.get();
			if (l != null) l.onReading();
		});

		EXEC.execute(() -> {
			Result<IdCard, Exception> result;
			try {
				result = doRead(tag, key);
			} catch (Throwable t) {
				Log.e(TAG, "doRead threw", t);
				result = Result.err(t instanceof Exception ? (Exception) t : new RuntimeException(t));
			}
			final Result<IdCard, Exception> finalResult = result;
			MAIN.post(() -> {
				final IdReaderListener l = weak.get();
				if (l != null) l.onResult(finalResult);
			});
		});
	}

	private static Result<IdCard, Exception> doRead(Tag tag, MrzKey key) {
		if (tag == null) return Result.err(new IllegalArgumentException("tag is null"));
		if (key == null) return Result.err(new IllegalArgumentException("MRZ key is null"));

		final IsoDep iso = IsoDep.get(tag);
		if (iso == null) return Result.err(new IllegalStateException("Card is not ISO-DEP"));

		final Iso7816.StdTag std = new Iso7816.StdTag(iso);
		final IdCard card = new IdCard();

		try {
			std.connect();
			try {
				std.setTimeout(15000);
			} catch (Exception ignored) {
			}

			final byte[] selApp = Hex.concat(
					new byte[]{0x00, (byte) 0xA4, 0x04, 0x0C, (byte) AID_EMRTD.length},
					AID_EMRTD);
			final Iso7816.Response sr = std.transceive(selApp);
			if (!sr.isOkey()) {
				throw new RuntimeException("SELECT eMRTD SW=" + sr.getSw12String());
			}

			final Bac bac = Bac.mutualAuthenticate(std, Bac.mrzInfo(
					key.documentNumber, key.dateOfBirth, key.dateOfExpiry));
			final SecureMessaging sm = new SecureMessaging(bac);

			final byte[] com = readEf(sm, std, FID_COM);
			if (com != null) card.presentDataGroups = DgParser.parseCom(com);

			final byte[] dg1 = readEf(sm, std, FID_DG1);
			if (dg1 != null) Mrz.decodeDg1(dg1, card);

			final byte[] dg11 = tryReadEf(sm, std, FID_DG11);
			if (dg11 != null) DgParser.parseDg11(dg11, card);
			final byte[] dg12 = tryReadEf(sm, std, FID_DG12);
			if (dg12 != null) DgParser.parseDg12(dg12, card);
			final byte[] dg13 = tryReadEf(sm, std, FID_DG13);
			if (dg13 != null) DgParser.parseDg13(dg13, card);

			final byte[] dg2 = tryReadEf(sm, std, FID_DG2);
			if (dg2 != null) {
				final Dg2.Image img = Dg2.extract(dg2);
				card.photoFormat = img.format;
				card.photoBytes = img.data;
			}

			return Result.ok(card);
		} catch (Exception e) {
			Log.e(TAG, "doRead failed", e);
			return Result.err(e);
		} finally {
			try {
				std.close();
			} catch (Exception ignored) {
			}
		}
	}

	/** Like {@link #readEf} but swallows errors and returns null — used for optional DGs. */
	private static byte[] tryReadEf(SecureMessaging sm, Iso7816.StdTag tag, int fid) {
		try {
			return readEf(sm, tag, fid);
		} catch (Exception e) {
			Log.w(TAG, String.format("read EF %04X failed: %s", fid, e.getMessage()));
			return null;
		}
	}

	/**
	 * Select EF by FID, then read its full body in chunks. The first 4 bytes
	 * carry the outer TLV's tag + length, from which we compute the total size.
	 *
	 * @return null if the file is not present (SW 6A82), bytes otherwise
	 */
	private static byte[] readEf(SecureMessaging sm, Iso7816.StdTag tag, int fid) throws Exception {
		final byte[] sel = {
				0x00, (byte) 0xA4, 0x02, 0x0C, 0x02,
				(byte) ((fid >> 8) & 0xFF), (byte) (fid & 0xFF)
		};
		final Iso7816.Response sr = sm.transceive(tag, sel);
		final short sw = sr.getSw12();
		if (sw == (short) 0x6A82) return null;
		if (sw != Iso7816.SW_NO_ERROR) {
			throw new RuntimeException(String.format("SELECT FID %04X SW=%s", fid, sr.getSw12String()));
		}

		final byte[] head = readBinary(sm, tag, 0, HEAD_LEN);
		final int total = parseTlvLen(head);
		final ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.write(head, 0, head.length);
		int off = head.length;
		while (off < total) {
			final int chunk = Math.min(MAX_CHUNK, total - off);
			final byte[] part = readBinary(sm, tag, off, chunk);
			// A successful READ BINARY that yields no bytes (EOF quirk, or a card
			// that returns 9000 with an empty body) would otherwise spin this loop
			// forever on the single reader thread. Stop and return what we have.
			if (part.length == 0) break;
			all.write(part, 0, part.length);
			off += part.length;
		}
		return all.toByteArray();
	}

	private static byte[] readBinary(SecureMessaging sm, Iso7816.StdTag tag, int offset, int len) throws Exception {
		// READ BINARY: 00 B0 P1(offset hi) P2(offset lo) Le. Top bit of P1 must be
		// 0 for "current EF, no SFI" addressing, so this form can only express a
		// 15-bit offset (0..0x7FFF). Masking a larger offset with 0x7F would wrap
		// to a low address and silently return the wrong bytes — fail clearly
		// instead. (Files > 32 KB would need odd-INS B1 / extended addressing.)
		if (offset < 0 || offset > 0x7FFF) {
			throw new IllegalArgumentException("READ BINARY offset out of range: " + offset);
		}
		final byte[] cmd = {0x00, (byte) 0xB0, (byte) ((offset >> 8) & 0x7F), (byte) (offset & 0xFF), (byte) len};
		final Iso7816.Response r = sm.transceive(tag, cmd);
		if (!r.isOkey()) {
			throw new RuntimeException("READ BINARY SW=" + r.getSw12String());
		}
		return r.getBytes();
	}

	/** Read the outer-TLV header (1-byte tag + length-of-length) and compute total file size. */
	private static int parseTlvLen(byte[] head) {
		int i = 1; // skip 1-byte tag (DG tags are all in 0x61–0x76)
		if (i >= head.length) return head.length;
		int b = head[i++] & 0xFF;
		int n;
		if ((b & 0x80) == 0) {
			n = b;
		} else {
			final int count = b & 0x7F;
			n = 0;
			for (int j = 0; j < count; ++j) {
				if (i >= head.length) break; // truncated header — use what we parsed
				n = (n << 8) | (head[i++] & 0xFF);
			}
		}
		return i + n;
	}

}
