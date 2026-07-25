/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.bac;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.adkhambek.reader.iso7816.Hex;
import com.adkhambek.reader.iso7816.Iso7816;

/**
 * ICAO 9303 Basic Access Control. Holds the derived session keys (KS_ENC, KS_MAC)
 * and the initial Send Sequence Counter after a successful EXTERNAL AUTHENTICATE.
 *
 * <p>Flow:
 * <ol>
 *   <li>Build {@code MRZ_information} = docNumber + cd1 + DOB + cd2 + expiry + cd3.</li>
 *   <li>{@code K_seed = SHA-1(MRZ_information)[0:16]}; derive {@code K_ENC = KDF(K_seed,1)}
 *       and {@code K_MAC = KDF(K_seed,2)}.</li>
 *   <li>{@code GET CHALLENGE} → 8-byte RND.ICC.</li>
 *   <li>Generate 8-byte RND.IFD and 16-byte K.IFD.</li>
 *   <li>S = RND.IFD ∥ RND.ICC ∥ K.IFD.  E.IFD = 3DES-CBC-Encrypt(K_ENC, IV=0, S).
 *       M.IFD = MAC(K_MAC, E.IFD). Send {@code MUTUAL AUTHENTICATE} with E.IFD ∥ M.IFD.</li>
 *   <li>Response contains E.ICC ∥ M.ICC. Verify MAC, decrypt to get
 *       RND.ICC ∥ RND.IFD ∥ K.ICC. Verify both nonces match.</li>
 *   <li>{@code K_session = K.IFD XOR K.ICC}; KS_ENC, KS_MAC derived from K_session.</li>
 *   <li>{@code SSC = RND.ICC[4:8] ∥ RND.IFD[4:8]}.</li>
 * </ol>
 */
public final class Bac {
	public final byte[] ksEnc;
	public final byte[] ksMac;
	public final byte[] ssc;

	private Bac(byte[] ksEnc, byte[] ksMac, byte[] ssc) {
		this.ksEnc = ksEnc;
		this.ksMac = ksMac;
		this.ssc = ssc;
	}

	public static String mrzInfo(String docNumber, String dob, String expiry) {
		// MRZ data is ASCII; Locale.ROOT avoids locale-specific uppercasing
		// (Turkish 'i' → 'İ' would break the check digit calculation).
		String doc = docNumber.toUpperCase(Locale.ROOT);
		if (doc.length() < 9) {
			final StringBuilder sb = new StringBuilder(doc);
			while (sb.length() < 9) sb.append('<');
			doc = sb.toString();
		}
		return doc + checkDigit(doc) + dob + checkDigit(dob) + expiry + checkDigit(expiry);
	}

	public static char checkDigit(String s) {
		final int[] w = {7, 3, 1};
		int sum = 0;
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			final int v;
			if (c >= '0' && c <= '9') v = c - '0';
			else if (c >= 'A' && c <= 'Z') v = (c - 'A') + 10;
			else if (c == '<') v = 0;
			else throw new IllegalArgumentException("bad MRZ char: " + c);
			sum += w[i % 3] * v;
		}
		return (char) ('0' + (sum % 10));
	}

	public static byte[] kSeed(String mrzInfo) {
		try {
			final MessageDigest md = MessageDigest.getInstance("SHA-1");
			final byte[] h = md.digest(mrzInfo.getBytes("UTF-8"));
			return Hex.slice(h, 0, 16);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static byte[] deriveKey(byte[] kSeed, int c) {
		try {
			final byte[] cb = {0, 0, 0, (byte) c};
			final MessageDigest md = MessageDigest.getInstance("SHA-1");
			md.update(kSeed);
			md.update(cb);
			final byte[] k = Hex.slice(md.digest(), 0, 16);
			adjustParity(k);
			return k;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** Set each byte's low bit so that the byte has odd parity (DES key parity). */
	private static void adjustParity(byte[] k) {
		for (int i = 0; i < k.length; ++i) {
			final int b = k[i] & 0xFE;
			int parity = 1;
			for (int j = 1; j < 8; ++j) parity ^= (b >> j) & 1;
			k[i] = (byte) (b | parity);
		}
	}

	public static Bac mutualAuthenticate(Iso7816.StdTag tag, String mrzInfo) throws Exception {
		final byte[] kSeed = kSeed(mrzInfo);
		final byte[] kEnc = deriveKey(kSeed, 1);
		final byte[] kMac = deriveKey(kSeed, 2);

		final Iso7816.Response cr = tag.transceive(new byte[]{0x00, (byte) 0x84, 0x00, 0x00, 0x08});
		if (!cr.isOkey()) {
			throw new RuntimeException("GET CHALLENGE SW=" + cr.getSw12String());
		}
		final byte[] rndIcc = cr.getBytes();

		final SecureRandom rnd = new SecureRandom();
		final byte[] rndIfd = new byte[8];
		rnd.nextBytes(rndIfd);
		final byte[] kIfd = new byte[16];
		rnd.nextBytes(kIfd);

		final byte[] s = Hex.concat(rndIfd, rndIcc, kIfd);
		final byte[] eIfd = des3(kEnc, s, true, new byte[8]);
		final byte[] mIfd = Iso9797Mac.mac(kMac, eIfd);

		final byte[] cmdData = Hex.concat(eIfd, mIfd);
		final byte[] cmd = Hex.concat(
				new byte[]{0x00, (byte) 0x82, 0x00, 0x00, (byte) cmdData.length},
				cmdData,
				new byte[]{0x28});

		final Iso7816.Response ar = tag.transceive(cmd);
		if (!ar.isOkey()) {
			throw new RuntimeException("EXT.AUTH SW=" + ar.getSw12String());
		}
		final byte[] payload = ar.getBytes();
		if (payload.length < 40) {
			throw new RuntimeException("EXT.AUTH short response");
		}
		final byte[] eIcc = Hex.slice(payload, 0, 32);
		final byte[] mIcc = Hex.slice(payload, 32, 8);
		if (!Arrays.equals(mIcc, Iso9797Mac.mac(kMac, eIcc))) {
			throw new RuntimeException("BAC MAC mismatch");
		}

		final byte[] r2 = des3(kEnc, eIcc, false, new byte[8]);
		final byte[] rndIccBack = Hex.slice(r2, 0, 8);
		final byte[] rndIfdBack = Hex.slice(r2, 8, 8);
		final byte[] kIcc = Hex.slice(r2, 16, 16);
		if (!Arrays.equals(rndIfdBack, rndIfd)) throw new RuntimeException("RND.IFD mismatch");
		if (!Arrays.equals(rndIccBack, rndIcc)) throw new RuntimeException("RND.ICC mismatch");

		final byte[] kSession = Hex.xor(kIfd, kIcc);
		final byte[] ksEnc = deriveKey(kSession, 1);
		final byte[] ksMac = deriveKey(kSession, 2);
		final byte[] ssc = Hex.concat(Hex.slice(rndIcc, 4, 4), Hex.slice(rndIfd, 4, 4));
		return new Bac(ksEnc, ksMac, ssc);
	}

	/** 2-key 3DES: expand 16-byte key to 24-byte K1∥K2∥K1, run CBC/NoPadding. */
	public static byte[] des3(byte[] key16, byte[] data, boolean encrypt, byte[] iv) {
		try {
			final byte[] k24 = Hex.concat(key16, Hex.slice(key16, 0, 8));
			final Cipher c = Cipher.getInstance("DESede/CBC/NoPadding");
			c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
					new SecretKeySpec(k24, "DESede"),
					new IvParameterSpec(iv));
			return c.doFinal(data);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
