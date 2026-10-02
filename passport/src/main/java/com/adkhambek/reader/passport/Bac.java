/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ICAO 9303 Basic Access Control: derive keys from the MRZ, authenticate
 * mutually with the chip, and hold the resulting session keys and SSC.
 *
 * <ol>
 *   <li>{@code K_seed = SHA-1(docNo ‖ cd ‖ dob ‖ cd ‖ expiry ‖ cd)[0:16]};
 *       {@code K_ENC = KDF(K_seed, 1)}, {@code K_MAC = KDF(K_seed, 2)}.</li>
 *   <li>GET CHALLENGE → RND.ICC. Send E.IFD ‖ M.IFD where
 *       {@code E.IFD = 3DES(K_ENC, RND.IFD ‖ RND.ICC ‖ K.IFD)}.</li>
 *   <li>Verify the chip's MAC and both nonces; {@code K_session = K.IFD ⊕ K.ICC}.</li>
 *   <li>{@code SSC = RND.ICC[4:8] ‖ RND.IFD[4:8]}.</li>
 * </ol>
 */
final class Bac {
	final byte[] ksEnc;
	final byte[] ksMac;
	final byte[] ssc;

	/** BAC did not complete; the reader reports it as {@code AUTH_FAILED}. */
	static final class BacException extends Exception {
		BacException(String message) {
			super(message);
		}
	}

	private Bac(byte[] ksEnc, byte[] ksMac, byte[] ssc) {
		this.ksEnc = ksEnc;
		this.ksMac = ksMac;
		this.ssc = ssc;
	}

	static String mrzInfo(String docNumber, String dob, String expiry) {
		// Locale.ROOT: Turkish 'i' → 'İ' would break the check digit.
		final StringBuilder doc = new StringBuilder(docNumber.toUpperCase(Locale.ROOT));
		while (doc.length() < 9) doc.append('<');
		return doc.toString() + checkDigit(doc.toString()) + dob + checkDigit(dob)
				+ expiry + checkDigit(expiry);
	}

	static char checkDigit(String s) {
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

	static byte[] kSeed(String mrzInfo) {
		return Arrays.copyOf(sha1(mrzInfo.getBytes(StandardCharsets.US_ASCII)), 16);
	}

	static byte[] deriveKey(byte[] seed, int counter) {
		final byte[] k = Arrays.copyOf(sha1(Bytes.concat(seed, new byte[]{0, 0, 0, (byte) counter})), 16);
		// DES key parity: each byte gets odd parity in its low bit.
		for (int i = 0; i < k.length; ++i) {
			final int b = k[i] & 0xFE;
			k[i] = (byte) (b | ((Integer.bitCount(b) & 1) ^ 1));
		}
		return k;
	}

	static Bac mutualAuthenticate(Apdu apdu, String mrzInfo) throws BacException, IOException {
		final byte[] seed = kSeed(mrzInfo);
		final byte[] kEnc = deriveKey(seed, 1);
		final byte[] kMac = deriveKey(seed, 2);

		final byte[] challenge = apdu.send(new byte[]{0x00, (byte) 0x84, 0x00, 0x00, 0x08});
		if (!Apdu.ok(challenge)) {
			throw new BacException(String.format("GET CHALLENGE SW=%04X", Apdu.sw(challenge)));
		}
		final byte[] rndIcc = Apdu.data(challenge);

		final SecureRandom random = new SecureRandom();
		final byte[] rndIfd = new byte[8];
		final byte[] kIfd = new byte[16];
		random.nextBytes(rndIfd);
		random.nextBytes(kIfd);

		final byte[] eIfd = des3(kEnc, Bytes.concat(rndIfd, rndIcc, kIfd), true);
		final byte[] mIfd = Iso9797Mac.mac(kMac, eIfd);
		final byte[] auth = apdu.send(Bytes.concat(
				new byte[]{0x00, (byte) 0x82, 0x00, 0x00, 0x28}, eIfd, mIfd, new byte[]{0x28}));
		if (!Apdu.ok(auth)) {
			throw new BacException(String.format("EXTERNAL AUTHENTICATE SW=%04X", Apdu.sw(auth)));
		}
		final byte[] payload = Apdu.data(auth);
		if (payload.length < 40) throw new BacException("EXTERNAL AUTHENTICATE short response");

		final byte[] eIcc = Arrays.copyOfRange(payload, 0, 32);
		if (!MessageDigest.isEqual(Arrays.copyOfRange(payload, 32, 40), Iso9797Mac.mac(kMac, eIcc))) {
			throw new BacException("BAC MAC mismatch");
		}
		final byte[] r = des3(kEnc, eIcc, false);
		if (!Arrays.equals(Arrays.copyOfRange(r, 0, 8), rndIcc)) throw new BacException("RND.ICC mismatch");
		if (!Arrays.equals(Arrays.copyOfRange(r, 8, 16), rndIfd)) throw new BacException("RND.IFD mismatch");

		final byte[] kSession = Bytes.xor(kIfd, Arrays.copyOfRange(r, 16, 32));
		return new Bac(deriveKey(kSession, 1), deriveKey(kSession, 2),
				Bytes.concat(Arrays.copyOfRange(rndIcc, 4, 8), Arrays.copyOfRange(rndIfd, 4, 8)));
	}

	/** Two-key 3DES-CBC, zero IV, no padding: K1 ‖ K2 ‖ K1. */
	static byte[] des3(byte[] key16, byte[] data, boolean encrypt) {
		try {
			final Cipher c = Cipher.getInstance("DESede/CBC/NoPadding");
			c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
					new SecretKeySpec(Bytes.concat(key16, Arrays.copyOf(key16, 8)), "DESede"),
					new IvParameterSpec(new byte[8]));
			return c.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("3DES unavailable", e);
		}
	}

	private static byte[] sha1(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-1").digest(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-1 unavailable", e);
		}
	}
}
