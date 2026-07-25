/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.nfc.id.bac;

import android.annotation.SuppressLint;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import com.adkhambek.reader.iso7816.Hex;

/**
 * ISO 9797-1 Algorithm 3 (Retail MAC) with DES, single-key K1 and K2 from a
 * 16-byte key, ISO 7816-4 padding applied by the caller. This is the MAC scheme
 * BAC and ICAO 9303 Secure Messaging both use.
 *
 * <pre>
 *   h₀ = 0⁸
 *   hᵢ = ENC_K1(hᵢ₋₁ ⊕ blockᵢ)         (CBC-MAC with K1)
 *   MAC = ENC_K1(DEC_K2(h_n))           (3DES-style "retail" finalize)
 * </pre>
 */
public final class Iso9797Mac {
	private Iso9797Mac() {
	}

	// ECB is correct here: ISO 9797-1 Algorithm 3 is defined in terms of single
	// DES/ECB rounds; the chaining (CBC-MAC + 3DES-style finalize) is built by
	// this method, not by the cipher mode. Suppress lint's generic "no ECB" rule.
	@SuppressLint("GetInstance")
	public static byte[] mac(byte[] key16, byte[] data) {
		try {
			final byte[] k1 = Hex.slice(key16, 0, 8);
			final byte[] k2 = Hex.slice(key16, 8, 8);
			final byte[] padded = Hex.pad(data, 8);

			final Cipher encK1 = Cipher.getInstance("DES/ECB/NoPadding");
			encK1.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k1, "DES"));

			byte[] h = new byte[8];
			for (int i = 0; i < padded.length; i += 8) {
				final byte[] x = Hex.xor(h, Hex.slice(padded, i, 8));
				h = encK1.doFinal(x);
			}

			final Cipher decK2 = Cipher.getInstance("DES/ECB/NoPadding");
			decK2.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k2, "DES"));
			final byte[] tmp = decK2.doFinal(h);

			return encK1.doFinal(tmp);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
