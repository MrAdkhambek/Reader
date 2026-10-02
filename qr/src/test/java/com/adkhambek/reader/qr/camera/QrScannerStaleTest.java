/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QrScannerStaleTest {

	@Test
	public void currentGenerationIsDelivered() {
		assertFalse(QrScanner.isStale(false, 3, 3));
	}

	@Test
	public void supersededGenerationIsDropped() {
		assertTrue(QrScanner.isStale(false, 3, 4));
	}

	@Test
	public void shutDownIsDropped() {
		assertTrue(QrScanner.isStale(true, 3, 3));
	}
}
