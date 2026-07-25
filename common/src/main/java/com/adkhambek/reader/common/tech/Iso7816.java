/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.common.tech;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import com.adkhambek.reader.common.Hex;

/**
 * APDU framing + BER-TLV parsing used by both the EMV bank-card reader and the
 * ICAO 9303 eMRTD reader.
 *
 * <p>{@link StdTag#transceive(byte[])} handles SW chaining for 61xx/6Cxx with a
 * hard cap of 16 exchanges per logical APDU.
 */
public class Iso7816 {
	public static final byte[] EMPTY = new byte[0];
	public static final short SW_NO_ERROR = (short) 0x9000;

	protected byte[] data;

	protected Iso7816() {
		data = EMPTY;
	}

	protected Iso7816(byte[] bytes) {
		data = (bytes == null) ? EMPTY : bytes;
	}

	public boolean match(byte tag) {
		return data.length == 1 && data[0] == tag;
	}

	public boolean match(short tag) {
		if (data.length == 2) {
			final byte d0 = (byte) ((tag >> 8) & 0xFF);
			final byte d1 = (byte) (tag & 0xFF);
			return data[0] == d0 && data[1] == d1;
		}
		return (tag >= 0 && tag <= 0xFF) && match((byte) tag);
	}

	public int size() {
		return data.length;
	}

	public byte[] getBytes() {
		return data;
	}

	@Override
	public String toString() {
		return Hex.encode(data);
	}

	public static class Response extends Iso7816 {
		public static final byte[] EMPTY_RESPONSE = new byte[0];
		public static final byte[] ERROR = {0x6F, 0x00};

		public Response(byte[] bytes) {
			super((bytes == null || bytes.length < 2) ? ERROR : bytes);
		}

		public short getSw12() {
			final int n = data.length;
			return (short) (((data[n - 2] & 0xFF) << 8) | (data[n - 1] & 0xFF));
		}

		public String getSw12String() {
			final int n = data.length;
			return String.format("0x%02X%02X", data[n - 2] & 0xFF, data[n - 1] & 0xFF);
		}

		public boolean isOkey() {
			return getSw12() == SW_NO_ERROR;
		}

		@Override
		public int size() {
			return data.length - 2;
		}

		@Override
		public byte[] getBytes() {
			return isOkey() ? Arrays.copyOfRange(data, 0, size()) : EMPTY_RESPONSE;
		}

		/** Raw payload (without SW1 SW2) regardless of success — useful for BAC where
		 * the card returns useful data even with a non-9000 SW (it doesn't, but other
		 * commands using SM also unwrap on SW != 9000). */
		public byte[] getPayload() {
			return Arrays.copyOfRange(data, 0, data.length - 2);
		}
	}

	public static final class BerT extends Iso7816 {
		public static int test(byte[] b, int s) {
			if (b == null || s < 0 || s >= b.length) {
				throw new IllegalArgumentException("Invalid BER-T start");
			}
			if ((b[s] & 0x1F) != 0x1F) return 1;
			int len = 1;
			while (s + len < b.length) {
				final boolean more = (b[s + len] & 0x80) == 0x80;
				++len;
				if (!more) return len;
			}
			throw new IllegalArgumentException("Truncated BER-T");
		}

		public static BerT read(byte[] b, int s) {
			return new BerT(Arrays.copyOfRange(b, s, s + test(b, s)));
		}

		public BerT(byte[] bytes) {
			super(bytes);
		}

		public boolean hasChild() {
			return (data[0] & 0x20) == 0x20;
		}
	}

	public static final class BerL extends Iso7816 {
		private final int val;

		public static int test(byte[] b, int s) {
			if (b == null || s < 0 || s >= b.length) {
				throw new IllegalArgumentException("Invalid BER-L start");
			}
			if ((b[s] & 0x80) != 0x80) return 1;
			final int count = b[s] & 0x7F;
			if (count == 0 || count > 4 || s + count >= b.length) {
				throw new IllegalArgumentException("Invalid BER-L");
			}
			return 1 + count;
		}

		public static int calc(byte[] b, int s) {
			if ((b[s] & 0x80) != 0x80) return b[s];
			int v = 0;
			final int e = s + (b[s] & 0x7F);
			while (++s <= e) v = (v << 8) | (b[s] & 0xFF);
			return v;
		}

		public static BerL read(byte[] b, int s) {
			return new BerL(Arrays.copyOfRange(b, s, s + test(b, s)));
		}

		public BerL(byte[] bytes) {
			super(bytes);
			val = calc(bytes, 0);
		}

		public int toInt() {
			return val;
		}
	}

	public static final class BerV extends Iso7816 {
		public static BerV read(byte[] b, int s, int len) {
			return new BerV(Arrays.copyOfRange(b, s, s + len));
		}

		public BerV(byte[] bytes) {
			super(bytes);
		}
	}

	public static final class BerTLV extends Iso7816 {
		public final BerT t;
		public final BerL l;
		public final BerV v;

		public BerTLV(BerT t, BerL l, BerV v) {
			this.t = t;
			this.l = l;
			this.v = v;
		}

		public static BerTLV read(byte[] b, int s) {
			final int start = s;
			final BerT t = BerT.read(b, s);
			s += t.size();
			final BerL l = BerL.read(b, s);
			s += l.size();
			if (s + l.toInt() > b.length) {
				throw new IllegalArgumentException("Truncated BER-V");
			}
			final BerV v = BerV.read(b, s, l.toInt());
			s += v.size();
			final BerTLV tlv = new BerTLV(t, l, v);
			tlv.data = Arrays.copyOfRange(b, start, s);
			return tlv;
		}

		/** Build a BER-TLV from a 1- or 2-byte tag and value bytes. Used by Secure
		 * Messaging to assemble DO87 / DO97 / DO8E / DO99. Length is encoded
		 * short-form (<128), 1-byte long-form (128–255), or 2-byte long-form. */
		public static byte[] encode(int tag, byte[] value) {
			final byte[] tagBytes = (tag <= 0xFF)
					? new byte[]{(byte) tag}
					: new byte[]{(byte) ((tag >> 8) & 0xFF), (byte) (tag & 0xFF)};
			final int len = value.length;
			final byte[] lenBytes;
			if (len < 0x80) {
				lenBytes = new byte[]{(byte) len};
			} else if (len <= 0xFF) {
				lenBytes = new byte[]{(byte) 0x81, (byte) len};
			} else {
				lenBytes = new byte[]{(byte) 0x82, (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF)};
			}
			final byte[] out = new byte[tagBytes.length + lenBytes.length + len];
			System.arraycopy(tagBytes, 0, out, 0, tagBytes.length);
			System.arraycopy(lenBytes, 0, out, tagBytes.length, lenBytes.length);
			System.arraycopy(value, 0, out, tagBytes.length + lenBytes.length, len);
			return out;
		}

		public static void extractPrimitives(BerHouse out, byte[] data) {
			int s = 0;
			while (s < data.length) {
				if (data[s] == 0x00 || data[s] == (byte) 0xFF) {
					++s;
					continue;
				}
				final BerTLV tlv = read(data, s);
				if (tlv.t.hasChild()) extractPrimitives(out, tlv.v.getBytes());
				else out.add(tlv);
				s += tlv.size();
			}
		}

		public static void extractPrimitives(BerHouse out, Iso7816 obj) {
			extractPrimitives(out, obj.getBytes());
		}
	}

	public static final class BerHouse {
		final ArrayList<BerTLV> tlvs = new ArrayList<>();

		public int count() {
			return tlvs.size();
		}

		public BerTLV get(int index) {
			return tlvs.get(index);
		}

		public void add(BerTLV tlv) {
			tlvs.add(tlv);
		}

		public static BerHouse from(byte[] data) {
			final BerHouse h = new BerHouse();
			BerTLV.extractPrimitives(h, data);
			return h;
		}

		public BerTLV findFirst(byte tag) {
			for (final BerTLV tlv : tlvs) if (tlv.t.match(tag)) return tlv;
			return null;
		}

		public BerTLV findFirst(short tag) {
			for (final BerTLV tlv : tlvs) if (tlv.t.match(tag)) return tlv;
			return null;
		}

		/** Convenience for tags > 32767 that don't fit in a signed short literal. */
		public BerTLV findFirst(int tag) {
			if (tag >= 0 && tag <= 0xFF) return findFirst((byte) tag);
			return findFirst((short) tag);
		}

		public ArrayList<BerTLV> findAll(byte tag) {
			final ArrayList<BerTLV> ret = new ArrayList<>();
			for (final BerTLV tlv : tlvs) if (tlv.t.match(tag)) ret.add(tlv);
			return ret;
		}

		public ArrayList<BerTLV> findAll(short tag) {
			final ArrayList<BerTLV> ret = new ArrayList<>();
			for (final BerTLV tlv : tlvs) if (tlv.t.match(tag)) ret.add(tlv);
			return ret;
		}
	}

	public static final class StdTag {
		private static final byte CH_STA_MORE = (byte) 0x61;
		private static final byte CH_STA_LE = (byte) 0x6C;
		private static final byte[] CMD_GETRESPONSE = {0x00, (byte) 0xC0, 0x00, 0x00, 0x00};

		private final IsoDep iso;

		public StdTag(IsoDep iso) {
			this.iso = iso;
		}

		public void connect() throws IOException {
			iso.connect();
		}

		public void close() throws IOException {
			iso.close();
		}

		public void setTimeout(int ms) {
			iso.setTimeout(ms);
		}

		public boolean isConnected() {
			return iso.isConnected();
		}

		public Response transceive(byte[] cmd) throws IOException {
			final ByteArrayOutputStream rsp = new ByteArrayOutputStream();
			byte[] c = cmd.clone();
			int exchanges = 0;
			while (true) {
				if (++exchanges > 16) {
					throw new IOException("Too many chained APDU responses");
				}
				final byte[] r = iso.transceive(c);
				if (r == null) {
					Log.w("NFCard", "<< null response");
					return new Response(Response.ERROR);
				}
				final int swR = r.length - 2;
				if (swR < 0) return new Response(r);

				final byte sw1 = r[swR];
				final byte sw2 = r[swR + 1];

				// 6Cxx: wrong Le, retry same APDU with Le=sw2. No append.
				if (sw1 == CH_STA_LE) {
					c[c.length - 1] = sw2;
					continue;
				}

				if (swR > 0) rsp.write(r, 0, swR);

				if (sw1 != CH_STA_MORE) {
					rsp.write(sw1);
					rsp.write(sw2);
					return new Response(rsp.toByteArray());
				}

				c = CMD_GETRESPONSE.clone();
				c[c.length - 1] = sw2;
			}
		}
	}
}
