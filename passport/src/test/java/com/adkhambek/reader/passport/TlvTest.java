/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class TlvTest {

	private static void assertMalformed(String hex) {
		try {
			Tlv.primitives(Replay.hex(hex));
			fail("expected IllegalArgumentException for " + hex);
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test public void shortFormLength() {
		final List<Tlv> t = Tlv.primitives(Replay.hex("5A021234"));
		assertEquals(0x5A, t.get(0).tag);
		assertArrayEquals(Replay.hex("1234"), t.get(0).value);
	}

	@Test public void longFormLengthOneByte() {
		final byte[] in = new byte[3 + 0x80];
		in[0] = 0x5A;
		in[1] = (byte) 0x81;
		in[2] = (byte) 0x80;
		assertEquals(0x80, Tlv.primitives(in).get(0).value.length);
	}

	@Test public void longFormLengthTwoBytes() {
		final byte[] in = new byte[4 + 0x100];
		in[0] = 0x5A;
		in[1] = (byte) 0x82;
		in[2] = 0x01;
		in[3] = 0x00;
		assertEquals(0x100, Tlv.primitives(in).get(0).value.length);
	}

	@Test public void multiByteTags() {
		assertEquals(0x9F38, Tlv.primitives(Replay.hex("9F38020001")).get(0).tag);
		assertEquals(1, Tlv.tagLength(Replay.hex("4F"), 0));
		assertEquals(2, Tlv.tagLength(Replay.hex("5F2A"), 0));
		assertEquals(2, Tlv.tagLength(Replay.hex("BF0C"), 0));
	}

	@Test public void truncatedMultiByteTagThrows() {
		assertMalformed("9F80");
	}

	@Test public void truncatedLengthThrows() {
		assertMalformed("4F8201");
	}

	@Test public void truncatedValueThrows() {
		assertMalformed("4F0501");
	}

	/** PPSE-shaped FCI: 6F { 84, A5 { BF0C { 61 { 4F } } } }. */
	@Test public void descendsIntoConstructedObjects() {
		final List<Tlv> t = Tlv.primitives(Replay.hex(
				"6F1A840E325041592E5359532E4444463031A508BF0C0561034F01AA"));
		assertArrayEquals(Replay.hex("AA"), Tlv.find(t, 0x4F).value);
		assertNotNull(Tlv.find(t, 0x84));
		assertEquals(1, Tlv.findAll(t, 0x4F).size());
	}

	@Test public void skipsZeroAndFFFiller() {
		final List<Tlv> t = Tlv.primitives(Replay.hex("00004F02ABCDFFFF"));
		assertEquals(1, t.size());
		assertArrayEquals(Replay.hex("ABCD"), t.get(0).value);
	}

	@Test public void parseWithoutDescendKeepsConstructedWhole() {
		final List<Tlv> t = Tlv.parse(Replay.hex("6F034F01AA"), false);
		assertEquals(1, t.size());
		assertEquals(0x6F, t.get(0).tag);
		assertArrayEquals(Replay.hex("4F01AA"), t.get(0).value);
	}

	@Test public void findReturnsNullWhenAbsent() {
		assertEquals(null, Tlv.find(Tlv.primitives(Replay.hex("5A021234")), 0x57));
	}
}
