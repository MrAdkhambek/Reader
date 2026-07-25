/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.iso7816;

import android.nfc.tech.IsoDep;

import java.io.IOException;

/** {@link ApduChannel} backed by an Android NFC ISO-DEP tag. */
public final class IsoDepChannel implements ApduChannel {

	private final IsoDep isoDep;

	public IsoDepChannel(IsoDep isoDep) {
		if (isoDep == null) throw new IllegalArgumentException("isoDep is null");
		this.isoDep = isoDep;
	}

	@Override
	public void connect() throws IOException {
		if (!isoDep.isConnected()) isoDep.connect();
	}

	@Override
	public byte[] transceive(byte[] apdu) throws IOException {
		return isoDep.transceive(apdu);
	}

	@Override
	public void setTimeout(int millis) {
		isoDep.setTimeout(millis);
	}

	@Override
	public void close() throws IOException {
		isoDep.close();
	}
}
