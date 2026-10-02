/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport.bac;

import android.annotation.SuppressLint;

import com.adkhambek.reader.passport.iso7816.Bytes;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ISO 9797-1 MAC Algorithm 3 ("retail MAC") with DES, keys K1 ‖ K2 from a
 * 16-byte key, ISO 7816-4 padding. Used by BAC and Secure Messaging.
 *
 * <pre>
 *   h   = last block of DES-CBC_K1(IV = 0, padded data)
 *   MAC = DES_K1(DES⁻¹_K2(h))
 * </pre>
 */
final class Iso9797Mac {
	private Iso9797Mac() {
	}

	// ECB is correct here: the finalisation is two single-block DES operations,
	// defined by the algorithm, not a mode choice. Suppress lint's generic "no ECB" rule.
	@SuppressLint("GetInstance")
	static byte[] mac(byte[] key16, byte[] data) {
		try {
			final SecretKeySpec k1 = new SecretKeySpec(key16, 0, 8, "DES");
			final SecretKeySpec k2 = new SecretKeySpec(key16, 8, 8, "DES");

			final Cipher cbc = Cipher.getInstance("DES/CBC/NoPadding");
			cbc.init(Cipher.ENCRYPT_MODE, k1, new IvParameterSpec(new byte[8]));
			final byte[] chained = cbc.doFinal(Bytes.pad(data));
			final byte[] h = Arrays.copyOfRange(chained, chained.length - 8, chained.length);

			final Cipher ecb = Cipher.getInstance("DES/ECB/NoPadding");
			ecb.init(Cipher.DECRYPT_MODE, k2);
			final byte[] t = ecb.doFinal(h);
			ecb.init(Cipher.ENCRYPT_MODE, k1);
			return ecb.doFinal(t);
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("DES unavailable", e);
		}
	}
}
