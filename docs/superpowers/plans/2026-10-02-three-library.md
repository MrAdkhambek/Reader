# Read3r 3.0 — Three Libraries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `{common, iso7816, nfc, qr}` with three independent Java Android libraries — `qr`, `passport`, `card` — each exposing a blocking read plus an `Executor` + per-call `Callback`.

**Architecture:** Each NFC library carries its own private copy of a small APDU/TLV helper set (identical files apart from the package line), so no library depends on another. Readers are instances: the blocking `read` does the I/O; the async `read` runs it on a work `Executor` and delivers once on a callback `Executor`, returning a `Cancellable`. New libraries are built alongside the old modules, which are deleted last, so the build stays green after every task.

**Tech Stack:** Java 17, AGP 8.7.3, Gradle 8.10.2, JUnit 4.13.2, CameraX 1.3.4, ZXing 3.5.3, vanniktech maven-publish 0.30.0.

**Spec:** `docs/superpowers/specs/2026-10-02-three-library-design.md`

## Global Constraints

- Group `com.adkhambek.reader`; artifacts `qr`, `passport`, `card`; version `3.0.0` (root `gradle.properties` `VERSION_NAME`, set in Task 7).
- Exactly three published artifacts. None depends on another.
- Java only in the libraries. No Kotlin, no coroutines.
- `compileSdk = 34`, `minSdk = 21`, Java 17 — all via the `read3r.android-library` convention plugin. Module build files declare only `plugins`, `android { namespace }`, `dependencies`.
- Every version lives in `gradle/libs.versions.toml`.
- Every published module has `gradle.properties` with `POM_ARTIFACT_ID`, `POM_NAME`, `POM_DESCRIPTION`.
- `lint { warningsAsErrors = true }` everywhere (already in the convention plugin). Suppressions are commented where declared.
- **Java source is indented with TABS. Gradle `.kts` files use 4 spaces.**
- Every Java file starts with `/* SPDX-License-Identifier: Apache-2.0 */`.
- Any `String.format` with `%d` passes `Locale.ROOT`.
- No library performs network I/O.
- `./gradlew test lint` is green at the end of every task. Run Gradle **without** `--offline` (lint's jars are not in the offline cache).
- **Twin files.** `Apdu`, `Tlv`, `Transceiver`, `Call`, `Callback`, `Cancellable`, `ReadException` (main) and `ApduTest`, `TlvTest`, `CallTest`, `Replay`, `ReplayTest` (test) exist in both `card` and `passport`. They must be identical apart from line 2 (`package …`). Library-specific helpers live elsewhere.
- Helper classes are **package-private in the library's main package** (`com.adkhambek.reader.card`, `com.adkhambek.reader.passport`, `com.adkhambek.reader.qr`). There are no `.internal` packages; apps cannot see helpers at all.

## Review Focus

The inputs most likely to bite a user that the spec implies but does not spell out. Each has a test in the task named.

1. **An MRZ scanned from a QR code arrives with line breaks** — `Mrz.isValid` / `Mrz.decode` must ignore whitespace (Task 3, `MrzTest`).
2. **The user types a document number BAC cannot encode** (`AB-123`, an embedded space) — `MrzKey` must reject it at construction with `IllegalArgumentException`, not fail mid-read as `FAILED` (Task 3, `MrzKeyTest`).
3. **The chip answers a secured command with a bare, unprotected error status** (`6A82` for an absent file) — it must read as that status, not as a MAC failure (Task 3, `SecureMessagingTest`).
4. **`cancel()` runs on the main thread after the read finished but before the queued delivery** — the callback must not fire (Tasks 2 and 3, `CallTest`).
5. **One EMV record is malformed** — the card must still be read from the other records (Task 2, `CardReaderTest`).

---

## File Structure

**`card/`** — `com.adkhambek.reader.card`

| File | Visibility | Responsibility |
|---|---|---|
| `CardReader.java` | public | Blocking + async read; ISO-DEP connect/close; maps errors to `ReadException` |
| `Card.java`, `CardApp.java`, `Currency.java` | public | Result types (moved from `nfc`) |
| `Callback.java`, `Cancellable.java`, `ReadException.java` | public | Async contract + error type (twins) |
| `Emv.java` | package | PPSE → AID → GPO → AFL records → `CardApp` |
| `Apdu.java`, `Tlv.java`, `Transceiver.java`, `Call.java` | package | APDU chaining, BER-TLV, test seam, executor plumbing (twins) |
| `Bytes.java` | package | Hex encode |

**`passport/`** — `com.adkhambek.reader.passport`

| File | Visibility | Responsibility |
|---|---|---|
| `PassportReader.java` | public | Blocking + async read; SELECT, BAC, data groups; error mapping |
| `Passport.java`, `PersonalDetails.java`, `DocumentDetails.java`, `Photo.java`, `PhotoFormat.java`, `MrzKey.java` | public | Result and input types |
| `Mrz.java`, `MrzDocument.java`, `MrzFormatException.java` | public | MRZ decode/validate (from the `mrz/` work in progress) |
| `Callback.java`, `Cancellable.java`, `ReadException.java` | public | Twins |
| `Bac.java` | package | BAC key derivation and mutual authentication |
| `Iso9797Mac.java` | package | Retail MAC |
| `SecureMessaging.java` | package | Wrap/unwrap APDUs |
| `EfReader.java` | package | SELECT + chunked READ BINARY of one EF |
| `DgParser.java`, `Dg2.java` | package | EF.COM, DG1, DG11–13 parsing; DG2 image extraction |
| `Apdu.java`, `Tlv.java`, `Transceiver.java`, `Call.java` | package | Twins |
| `Bytes.java` | package | Hex, concat, xor, ISO 7816-4 pad/unpad |

**`qr/`** — `com.adkhambek.reader.qr` (rewritten in place)

| File | Visibility | Responsibility |
|---|---|---|
| `QrScannerView.java` | public | Drop-in view: preview + overlay + lifecycle |
| `ScannerOverlayView.java` | public | Animated viewfinder (moved unchanged) |
| `QrCode.java` | public | Scan result (was `ScanResult`) |
| `QrScanner.java` | package | CameraX binding |
| `QrAnalyzer.java` | package | Frame → ZXing decode |
| `PreviewGeometry.java` | package | Image → view coordinate maths (moved unchanged) |

**Deleted by Task 7:** `common/`, `nfc/`, `iso7816/`, `build-logic/…/read3r.jvm-library.gradle.kts`, `build-logic/…/read3r.kotlin-library.gradle.kts`.

---

### Task 1: Green baseline and Gradle build flags

The working tree has an uncommitted `mrz/` module (tests only, no sources) and `":mrz"` in `settings.gradle.kts`, so the build is red. This task makes it green and turns on Gradle caching. `mrz/` itself stays on disk, untracked: Task 3 takes its `MrzTest.java`.

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`

**Interfaces:**
- Consumes: nothing.
- Produces: a green `./gradlew test lint`.

- [ ] **Step 1: Drop `:mrz` from the build**

In `settings.gradle.kts`, delete the line `    ":mrz",`. Leave the `mrz/` directory alone.

- [ ] **Step 2: Add the build flags**

Append to the root `gradle.properties`, directly below `org.gradle.jvmargs=…`:

```properties
# Reuse task outputs across builds and branches.
org.gradle.caching=true
# Independent modules build concurrently.
org.gradle.parallel=true
# Skip re-running build scripts when nothing they read has changed.
org.gradle.configuration-cache=true
```

- [ ] **Step 3: Verify, twice**

Run: `./gradlew test lint`, then run it again.
Expected: both BUILD SUCCESSFUL; the second prints `Reusing configuration cache.`

If the first run fails with configuration-cache problems, delete only the `org.gradle.configuration-cache` line and name the failing plugin in the commit body. Do not use `org.gradle.configuration-cache.problems=warn`.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts gradle.properties
git commit -m "build: enable build cache, parallel execution and configuration cache

Also drops the half-built :mrz include; its test moves into :passport."
```

---

### Task 2: `card` library

**Files:**
- Create: `card/build.gradle.kts`, `card/gradle.properties`, `card/src/main/AndroidManifest.xml`
- Create: `card/src/main/java/com/adkhambek/reader/card/{Transceiver,Apdu,Tlv,Bytes,ReadException,Callback,Cancellable,Call,Currency,Card,CardApp,Emv,CardReader}.java`
- Create: `card/src/test/java/com/adkhambek/reader/card/{Replay,ReplayTest,ApduTest,TlvTest,CallTest,EmvTest,CardReaderTest}.java`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: nothing from other modules.
- Produces (public): `CardReader()`, `CardReader(Executor work, Executor callbacks)`, `@WorkerThread Card read(Tag) throws ReadException`, `Cancellable read(Tag, Callback<Card>)`; `record Card(String uid, List<CardApp> apps)` with `isUnknown()`; `record CardApp(byte[] aid, String label, String pan, String panSequence, String cardholder, String country, Currency currency, String effectiveDate, String expiryDate, String appVersion)`; `enum Currency`; `interface Callback<T> { void onSuccess(T); void onError(ReadException); }`; `interface Cancellable { void cancel(); }`; `final class ReadException extends Exception` with `enum Reason { CARD_LOST, AUTH_FAILED, UNSUPPORTED, FAILED }`, `Reason reason()`, public constructors `(Reason, String)` and `(Reason, String, Throwable)`.
- Produces (package, twins reused verbatim by Task 3): `interface Transceiver { byte[] transceive(byte[]) throws IOException; }`; `Apdu(Transceiver)`, `byte[] send(byte[])`, `static int sw(byte[])`, `static boolean ok(byte[])`, `static byte[] data(byte[])`, `static final int SW_OK`; `Tlv` with fields `int tag`, `byte[] value`, `static List<Tlv> primitives(byte[])`, `static List<Tlv> parse(byte[], boolean descend)`, `static int tagLength(byte[], int)`, `static Tlv find(List<Tlv>, int)`, `static List<Tlv> findAll(List<Tlv>, int)`; `Call.start(Executor work, Executor callbacks, Callback<T>, Closeable connection, Call.Job<T>)` returning `Call<T>` (a `Cancellable`), `interface Call.Job<T> { T run() throws ReadException; }`. Test helper `Replay` with `expect(String)`, `reply(String)`, `assertExhausted()`, `static byte[] hex(String)`.
- Package seam used by tests: `static Card CardReader.readWith(String uid, Transceiver)`; `Emv` statics `read(Apdu)`, `gpoArgument(byte[])`, `aflFromFormat1(byte[])`, `extractPan`, `extractExpiry`, `ymFromBcd(List<Tlv>, int)`, `country`, `currency`, `identify(byte[])`.

- [ ] **Step 1: Create the module**

```bash
mkdir -p card/src/main/java/com/adkhambek/reader/card card/src/test/java/com/adkhambek/reader/card
```

`card/build.gradle.kts`:

```kotlin
plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.card"
}

dependencies {
    // @WorkerThread on the blocking read. CLASS retention: consumers do not
    // need it on their compile classpath, so implementation is enough.
    implementation(libs.androidx.annotation)
}
```

`card/gradle.properties`:

```properties
POM_ARTIFACT_ID=card
POM_NAME=Read3r Card
POM_DESCRIPTION=Reads EMV contactless bank cards (PAN, expiry, cardholder, currency) over Android NFC. Java API: blocking read, or Executor + callback.
```

`card/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.NFC" />
    <uses-feature android:name="android.hardware.nfc" android:required="false" />

</manifest>
```

In `settings.gradle.kts`, add `    ":card",` after `    ":app",`.

- [ ] **Step 2: Write the test helper and its tests (twins)**

`card/src/test/java/com/adkhambek/reader/card/Replay.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * A {@link Transceiver} that replays a scripted exchange. An APDU that does
 * not match the next scripted request throws {@link AssertionError}, so a
 * reader that changes what it sends fails loudly.
 *
 * <p>Every transcript in this test suite is SYNTHETIC: it encodes what the
 * implementation expects, not what a real card does.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Replay implements Transceiver {

	private final Deque<byte[][]> script = new ArrayDeque<>();
	private byte[] pending;

	static byte[] hex(String s) {
		final String c = s.replaceAll("\\s", "");
		final byte[] out = new byte[c.length() / 2];
		for (int i = 0; i < out.length; ++i) {
			out[i] = (byte) Integer.parseInt(c.substring(2 * i, 2 * i + 2), 16);
		}
		return out;
	}

	Replay expect(String request) {
		if (pending != null) throw new IllegalStateException("expect() twice without reply()");
		pending = hex(request);
		return this;
	}

	Replay reply(String response) {
		if (pending == null) throw new IllegalStateException("reply() without expect()");
		script.addLast(new byte[][]{pending, hex(response)});
		pending = null;
		return this;
	}

	@Override
	public byte[] transceive(byte[] apdu) {
		final byte[][] next = script.pollFirst();
		if (next == null) {
			throw new AssertionError("unexpected APDU, script exhausted: " + hexOf(apdu));
		}
		if (!Arrays.equals(next[0], apdu)) {
			throw new AssertionError("unexpected APDU\n  expected: " + hexOf(next[0])
					+ "\n  actual:   " + hexOf(apdu));
		}
		return next[1];
	}

	void assertExhausted() {
		if (!script.isEmpty()) {
			throw new AssertionError(script.size() + " scripted exchange(s) unused, next: "
					+ hexOf(script.peekFirst()[0]));
		}
	}

	private static String hexOf(byte[] b) {
		final StringBuilder sb = new StringBuilder(b.length * 2);
		for (final byte x : b) sb.append(String.format("%02X", x));
		return sb.toString();
	}
}
```

`card/src/test/java/com/adkhambek/reader/card/ReplayTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.fail;

import org.junit.Test;

/** The fixture must fail loudly, or every transcript test could pass vacuously. */
public class ReplayTest {

	@Test public void unexpectedApduFails() {
		final Replay card = new Replay().expect("00A4040000").reply("9000");
		try {
			card.transceive(Replay.hex("00B2010C00"));
			fail("expected AssertionError");
		} catch (AssertionError expected) {
		}
	}

	@Test public void leftoverExchangesFail() {
		final Replay card = new Replay()
				.expect("00A4040000").reply("9000")
				.expect("00B2010C00").reply("9000");
		card.transceive(Replay.hex("00A4040000"));
		try {
			card.assertExhausted();
			fail("expected AssertionError");
		} catch (AssertionError expected) {
		}
	}
}
```

- [ ] **Step 3: Write the failing `Apdu`, `Tlv` and `Call` tests (twins)**

`card/src/test/java/com/adkhambek/reader/card/ApduTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class ApduTest {

	private static final String SELECT = "00A4040007A000000003101000";

	@Test public void passesFinalResponseThrough() throws IOException {
		final Replay card = new Replay().expect(SELECT).reply("AABB9000");
		assertArrayEquals(Replay.hex("AABB9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	/** 61xx: fetch the rest with GET RESPONSE and append it. */
	@Test public void chains61xxWithGetResponse() throws IOException {
		final Replay card = new Replay()
				.expect(SELECT).reply("AABB6103")
				.expect("00C0000003").reply("CCDDEE9000");
		assertArrayEquals(Replay.hex("AABBCCDDEE9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	/** 6Cxx: resend the same command with Le corrected; the 6Cxx exchange adds nothing. */
	@Test public void retries6CxxWithCorrectedLe() throws IOException {
		final Replay card = new Replay()
				.expect(SELECT).reply("6C05")
				.expect("00A4040007A000000003101005").reply("AABBCCDDEE9000");
		assertArrayEquals(Replay.hex("AABBCCDDEE9000"), new Apdu(card).send(Replay.hex(SELECT)));
		card.assertExhausted();
	}

	@Test public void capsChainingAt16Exchanges() {
		final int[] calls = {0};
		final Apdu apdu = new Apdu(command -> {
			++calls[0];
			return Replay.hex("6100");
		});
		try {
			apdu.send(Replay.hex("00B0000000"));
			fail("expected IOException");
		} catch (IOException expected) {
		}
		assertEquals(16, calls[0]);
	}

	@Test public void shortResponseReadsAs6F00() throws IOException {
		final Apdu apdu = new Apdu(command -> new byte[]{(byte) 0x90});
		assertArrayEquals(Replay.hex("6F00"), apdu.send(Replay.hex(SELECT)));
	}

	@Test public void statusWordHelpers() {
		final byte[] r = Replay.hex("AABB6A82");
		assertEquals(0x6A82, Apdu.sw(r));
		assertFalse(Apdu.ok(r));
		assertArrayEquals(Replay.hex("AABB"), Apdu.data(r));
		assertTrue(Apdu.ok(Replay.hex("9000")));
	}
}
```

`card/src/test/java/com/adkhambek/reader/card/TlvTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

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
```

`card/src/test/java/com/adkhambek/reader/card/CallTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class CallTest {

	private static final Executor DIRECT = Runnable::run;

	private static final class Recorder<T> implements Callback<T> {
		final List<Object> events = new ArrayList<>();

		@Override
		public void onSuccess(T value) {
			events.add(value);
		}

		@Override
		public void onError(ReadException error) {
			events.add(error);
		}
	}

	private static final class Connection implements Closeable {
		boolean closed;

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test public void deliversSuccessOnce() {
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> "ok");
		assertEquals(Collections.singletonList("ok"), cb.events);
	}

	@Test public void deliversReadExceptionAsIs() {
		final ReadException e = new ReadException(ReadException.Reason.CARD_LOST, "gone");
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> {
			throw e;
		});
		assertSame(e, cb.events.get(0));
	}

	@Test public void wrapsRuntimeExceptionAsFailed() {
		final Recorder<String> cb = new Recorder<>();
		Call.start(DIRECT, DIRECT, cb, null, () -> {
			throw new IllegalStateException("boom");
		});
		final ReadException e = (ReadException) cb.events.get(0);
		assertEquals(ReadException.Reason.FAILED, e.reason());
		assertTrue(e.getCause() instanceof IllegalStateException);
	}

	@Test public void cancelBeforeRunSkipsTheJobAndClosesTheConnection() {
		final List<Runnable> queued = new ArrayList<>();
		final Connection connection = new Connection();
		final boolean[] ran = {false};
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(queued::add, DIRECT, cb, connection, () -> {
			ran[0] = true;
			return "ok";
		});
		call.cancel();
		queued.get(0).run();

		assertFalse(ran[0]);
		assertTrue(connection.closed);
		assertTrue(cb.events.isEmpty());
	}

	/** Review focus: the read finished and delivery is queued on the callback
	 *  thread, but cancel() gets there first. */
	@Test public void cancelAfterWorkButBeforeDeliverySuppressesTheCallback() {
		final List<Runnable> deliveries = new ArrayList<>();
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(DIRECT, deliveries::add, cb, null, () -> "ok");
		call.cancel();
		deliveries.get(0).run();

		assertTrue(cb.events.isEmpty());
	}

	@Test public void cancelAfterDeliveryIsANoOp() {
		final Connection connection = new Connection();
		final Recorder<String> cb = new Recorder<>();

		final Cancellable call = Call.start(DIRECT, DIRECT, cb, connection, () -> "ok");
		call.cancel();
		call.cancel();

		assertEquals(1, cb.events.size());
		assertFalse(connection.closed);
	}
}
```

- [ ] **Step 4: Run to verify they fail**

Run: `./gradlew :card:testDebugUnitTest`
Expected: compilation FAILS — `Transceiver`, `Apdu`, `Tlv`, `Call`, `Callback`, `Cancellable`, `ReadException` do not exist.

- [ ] **Step 5: Write the twin main files**

`card/src/main/java/com/adkhambek/reader/card/Transceiver.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.IOException;

/**
 * One raw APDU exchange. In production this is {@code isoDep::transceive}; in
 * tests it is a scripted replay.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
interface Transceiver {
	byte[] transceive(byte[] apdu) throws IOException;
}
```

`card/src/main/java/com/adkhambek/reader/card/Apdu.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Sends a command APDU and follows the ISO 7816-4 status-word protocol:
 * 61xx → GET RESPONSE for the remaining bytes; 6Cxx → resend with the
 * corrected Le. A response is {@code data ‖ SW1 SW2}.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Apdu {
	static final int SW_OK = 0x9000;
	private static final int MAX_EXCHANGES = 16;
	private static final byte[] ERROR = {0x6F, 0x00};

	private final Transceiver transceiver;

	Apdu(Transceiver transceiver) {
		this.transceiver = transceiver;
	}

	byte[] send(byte[] command) throws IOException {
		final ByteArrayOutputStream data = new ByteArrayOutputStream();
		byte[] c = command.clone();
		for (int i = 0; i < MAX_EXCHANGES; ++i) {
			final byte[] r = transceiver.transceive(c);
			if (r == null || r.length < 2) return ERROR.clone();
			final int n = r.length - 2;
			final byte sw1 = r[n];
			final byte sw2 = r[n + 1];
			if (sw1 == 0x6C) {
				// Wrong Le: resend with the one the card asked for, keep nothing.
				c[c.length - 1] = sw2;
				continue;
			}
			data.write(r, 0, n);
			if (sw1 != 0x61) {
				data.write(sw1);
				data.write(sw2);
				return data.toByteArray();
			}
			// 61xx: sw2 more bytes are waiting.
			c = new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, sw2};
		}
		throw new IOException("too many chained responses");
	}

	static int sw(byte[] response) {
		final int n = response.length;
		return ((response[n - 2] & 0xFF) << 8) | (response[n - 1] & 0xFF);
	}

	static boolean ok(byte[] response) {
		return sw(response) == SW_OK;
	}

	/** The response without its status word. */
	static byte[] data(byte[] response) {
		return Arrays.copyOf(response, response.length - 2);
	}
}
```

`card/src/main/java/com/adkhambek/reader/card/Tlv.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal BER-TLV reader. Tags are packed big-endian into an int, so
 * {@code 0x4F}, {@code 0x9F38} and {@code 0x5F2A} compare directly.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Tlv {
	final int tag;
	final byte[] value;

	Tlv(int tag, byte[] value) {
		this.tag = tag;
		this.value = value;
	}

	/** Every primitive object in {@code data}, descending into constructed ones. */
	static List<Tlv> primitives(byte[] data) {
		return parse(data, true);
	}

	/**
	 * The objects in {@code data}. With {@code descend}, a constructed object is
	 * replaced by its contents, recursively; without, it is returned whole.
	 * {@code 00} and {@code FF} filler between objects is skipped.
	 *
	 * @throws IllegalArgumentException on truncated or malformed input
	 */
	static List<Tlv> parse(byte[] data, boolean descend) {
		final List<Tlv> out = new ArrayList<>();
		parse(data, descend, out);
		return out;
	}

	private static void parse(byte[] b, boolean descend, List<Tlv> out) {
		int p = 0;
		while (p < b.length) {
			if (b[p] == 0x00 || b[p] == (byte) 0xFF) {
				++p;
				continue;
			}
			final boolean constructed = (b[p] & 0x20) != 0;
			final int tagLen = tagLength(b, p);
			int tag = 0;
			for (int i = 0; i < tagLen; ++i) tag = (tag << 8) | (b[p++] & 0xFF);

			if (p >= b.length) throw new IllegalArgumentException("truncated length");
			int len = b[p++] & 0xFF;
			if (len >= 0x80) {
				final int n = len & 0x7F;
				if (n == 0 || n > 3 || p + n > b.length) {
					throw new IllegalArgumentException("bad length");
				}
				len = 0;
				for (int i = 0; i < n; ++i) len = (len << 8) | (b[p++] & 0xFF);
			}
			if (p + len > b.length) throw new IllegalArgumentException("truncated value");

			final byte[] value = Arrays.copyOfRange(b, p, p + len);
			p += len;
			if (descend && constructed) parse(value, true, out);
			else out.add(new Tlv(tag, value));
		}
	}

	/** Bytes taken by the tag that starts at {@code p}. */
	static int tagLength(byte[] b, int p) {
		if ((b[p] & 0x1F) != 0x1F) return 1;
		int n = 1;
		while (p + n < b.length) {
			if ((b[p + n++] & 0x80) == 0) return n;
		}
		throw new IllegalArgumentException("truncated tag");
	}

	static Tlv find(List<Tlv> tlvs, int tag) {
		for (final Tlv t : tlvs) if (t.tag == tag) return t;
		return null;
	}

	static List<Tlv> findAll(List<Tlv> tlvs, int tag) {
		final List<Tlv> out = new ArrayList<>();
		for (final Tlv t : tlvs) if (t.tag == tag) out.add(t);
		return out;
	}
}
```

`card/src/main/java/com/adkhambek/reader/card/ReadException.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

/**
 * A card read did not complete. {@link #reason()} says what the caller can do
 * about it.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public final class ReadException extends Exception {

	public enum Reason {
		/** The card left the field mid-read. Ask the user to hold it still and retry. */
		CARD_LOST,
		/** Passport only: Basic Access Control failed — almost always a mistyped document number or date. */
		AUTH_FAILED,
		/** Not a card this reader understands: not ISO-DEP, no eMRTD applet, or no PPSE. */
		UNSUPPORTED,
		/** Anything else: an unexpected status word or malformed data. */
		FAILED,
	}

	private final Reason reason;

	public ReadException(Reason reason, String message) {
		this(reason, message, null);
	}

	public ReadException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
```

`card/src/main/java/com/adkhambek/reader/card/Callback.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

/**
 * The outcome of one asynchronous read, delivered on the reader's callback
 * executor (the main thread by default). Exactly one method is called, once,
 * unless the read is cancelled first.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public interface Callback<T> {
	void onSuccess(T value);

	void onError(ReadException error);
}
```

`card/src/main/java/com/adkhambek/reader/card/Cancellable.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

/**
 * Handle to one asynchronous read.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
public interface Cancellable {
	/**
	 * Stop the read and drop its callback. Closes the card connection, so a read
	 * in progress fails fast — and so does any other code using the same tag at
	 * that moment. Idempotent; does nothing once the callback has fired.
	 *
	 * <p>Called on the callback executor's thread (the main thread by default),
	 * the callback is guaranteed not to fire afterwards. Called from another
	 * thread, a delivery already running there may still complete.
	 */
	void cancel();
}
```

`card/src/main/java/com/adkhambek/reader/card/Call.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One asynchronous read: runs a blocking job on the work executor and
 * delivers its outcome once on the callback executor, unless cancelled.
 *
 * <p>Twin of the same file in the other NFC library (card ↔ passport) — keep
 * identical apart from the package line.
 */
final class Call<T> implements Cancellable, Runnable {

	interface Job<T> {
		T run() throws ReadException;
	}

	private final Executor callbacks;
	private final Closeable connection;
	private final Job<T> job;
	// Non-null until delivered or cancelled; getAndSet(null) makes each happen once.
	private final AtomicReference<Callback<T>> callback;

	private Call(Executor callbacks, Callback<T> callback, Closeable connection, Job<T> job) {
		this.callbacks = callbacks;
		this.callback = new AtomicReference<>(callback);
		this.connection = connection;
		this.job = job;
	}

	/** @param connection closed by {@link #cancel()}; may be null */
	static <T> Call<T> start(Executor work, Executor callbacks, Callback<T> callback,
			Closeable connection, Job<T> job) {
		final Call<T> call = new Call<>(callbacks, callback, connection, job);
		work.execute(call);
		return call;
	}

	@Override
	public void run() {
		if (callback.get() == null) return; // cancelled before it started
		T value = null;
		ReadException error = null;
		try {
			value = job.run();
		} catch (ReadException e) {
			error = e;
		} catch (RuntimeException e) {
			error = new ReadException(ReadException.Reason.FAILED, e.toString(), e);
		}
		final T v = value;
		final ReadException err = error;
		callbacks.execute(() -> {
			final Callback<T> cb = callback.getAndSet(null);
			if (cb == null) return; // cancelled while in flight
			if (err == null) cb.onSuccess(v);
			else cb.onError(err);
		});
	}

	@Override
	public void cancel() {
		if (callback.getAndSet(null) == null) return; // already delivered or cancelled
		if (connection == null) return;
		try {
			connection.close();
		} catch (IOException ignored) {
			// Closing is only to make the read fail fast; nothing to report.
		}
	}
}
```

- [ ] **Step 6: Run the twin tests**

Run: `./gradlew :card:testDebugUnitTest`
Expected: PASS — 2 (`ReplayTest`) + 6 (`ApduTest`) + 11 (`TlvTest`) + 6 (`CallTest`) = 25 tests.

- [ ] **Step 7: Move the result types**

```bash
cp nfc/src/main/java/com/adkhambek/reader/nfc/card/Currency.java card/src/main/java/com/adkhambek/reader/card/Currency.java
cp nfc/src/main/java/com/adkhambek/reader/nfc/card/bean/Card.java card/src/main/java/com/adkhambek/reader/card/Card.java
cp nfc/src/main/java/com/adkhambek/reader/nfc/card/bean/CardApp.java card/src/main/java/com/adkhambek/reader/card/CardApp.java
perl -pi -e 's/^package .*/package com.adkhambek.reader.card;/; s/^import com\.adkhambek\.reader\.nfc\.card\.Currency;\n//' \
   card/src/main/java/com/adkhambek/reader/card/{Currency,Card,CardApp}.java
```

`Card.java` and `CardApp.java` have no SPDX header and use 4-space indentation. Add `/* SPDX-License-Identifier: Apache-2.0 */` as their first line, and convert their leading 4-space indents to tabs:

```bash
for f in Card CardApp; do
  p=card/src/main/java/com/adkhambek/reader/card/$f.java
  perl -pi -e '1 while s/^(\t*)    /$1\t/' "$p"
  perl -0pi -e 's/\A/\/* SPDX-License-Identifier: Apache-2.0 *\/\n/' "$p"
done
```

Add a Javadoc to `Card`:

```java
/**
 * One tap of a contactless card: its NFC UID and the payment applications read
 * from it. {@code apps} is unmodifiable and never null.
 */
```

- [ ] **Step 8: Port the EMV tests (failing)**

```bash
cp nfc/src/test/java/com/adkhambek/reader/nfc/card/reader/EmvTest.java \
   card/src/test/java/com/adkhambek/reader/card/EmvTest.java
```

Edit `card/src/test/java/com/adkhambek/reader/card/EmvTest.java`:

1. Package becomes `com.adkhambek.reader.card`.
2. Delete the imports of `Currency`, `Iso7816.BerHouse`, `Iso7816.BerTLV`, `Iso7816.BerV`; add `import java.util.List;`.
3. Replace the `house` helper with:

```java
	private static List<Tlv> house(String tlvHex) {
		return Tlv.primitives(hex(tlvHex));
	}
```

4. Replace every `BerHouse h` with `List<Tlv> h`, every `EMV.` with `Emv.`, and every `(short) 0x` cast argument with a plain `0x` literal (`(short) 0x5F25` → `0x5F25`).
5. Add two tests for the GPO argument builder before the closing brace:

```java
	// --- GPO argument ------------------------------------------------------

	@Test public void gpoArgument_emptyPdol() {
		assertArrayEquals(hex("8300"), Emv.gpoArgument(new byte[0]));
	}

	/** TTQ (9F66) gets fixed terminal capabilities; unknown fields are zero-filled. */
	@Test public void gpoArgument_fillsTtqAndZeroes() {
		assertArrayEquals(hex("830A F6204000 000000000000"),
				Emv.gpoArgument(hex("9F6604 9F0206")));
	}
```

Run: `./gradlew :card:testDebugUnitTest`
Expected: compilation FAILS — `Emv` does not exist.

- [ ] **Step 9: Write `Bytes` and `Emv`**

`card/src/main/java/com/adkhambek/reader/card/Bytes.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

final class Bytes {
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private Bytes() {
	}

	/** Upper-case hex, two characters per byte. */
	static String hex(byte[] b) {
		final char[] out = new char[b.length * 2];
		for (int i = 0; i < b.length; ++i) {
			out[2 * i] = HEX[(b[i] >> 4) & 0xF];
			out[2 * i + 1] = HEX[b[i] & 0xF];
		}
		return new String(out);
	}
}
```

`card/src/main/java/com/adkhambek/reader/card/Emv.java` — the logic of `nfc/…/card/reader/EMV.java`, ported onto `Apdu`/`Tlv`, with logging removed and one behaviour change: a malformed record is skipped instead of failing the whole card.

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** EMV contactless read: PPSE → each AID → GPO → AFL records → {@link CardApp}. */
final class Emv {
	private static final byte[] PPSE = "2PAY.SYS.DDF01".getBytes(StandardCharsets.US_ASCII);

	private Emv() {
	}

	/** @return the card's payment applications, or null if the card has no PPSE */
	static List<CardApp> read(Apdu apdu) throws IOException {
		final byte[] ppse = apdu.send(select(PPSE));
		if (!Apdu.ok(ppse)) return null;
		final List<CardApp> apps = new ArrayList<>();
		for (final Tlv aid : Tlv.findAll(Tlv.primitives(Apdu.data(ppse)), 0x4F)) {
			final CardApp app = readApp(apdu, aid.value);
			if (app != null) apps.add(app);
		}
		return apps;
	}

	private static CardApp readApp(Apdu apdu, byte[] aid) throws IOException {
		final byte[] fci = apdu.send(select(aid));
		if (!Apdu.ok(fci)) return null;
		final List<Tlv> all = Tlv.primitives(Apdu.data(fci));

		final Tlv pdol = Tlv.find(all, 0x9F38);
		final byte[] gpo = apdu.send(gpoCommand(pdol == null ? new byte[0] : pdol.value));
		if (!Apdu.ok(gpo)) return null;

		final byte[] gpoData = Apdu.data(gpo);
		final byte[] afl;
		if (gpoData.length >= 2 && gpoData[0] == (byte) 0x80) {
			afl = aflFromFormat1(gpoData);
		} else {
			final List<Tlv> gpoTlvs = Tlv.primitives(gpoData);
			all.addAll(gpoTlvs);
			final Tlv t94 = Tlv.find(gpoTlvs, 0x94);
			afl = (t94 == null) ? null : t94.value;
		}
		if (afl == null) return null;

		for (int i = 0; i + 4 <= afl.length; i += 4) {
			final int sfi = (afl[i] & 0xFF) >>> 3;
			final int last = afl[i + 2] & 0xFF;
			for (int rec = afl[i + 1] & 0xFF; rec <= last; ++rec) {
				final byte[] r = apdu.send(
						new byte[]{0x00, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 0x04), 0x00});
				if (!Apdu.ok(r)) continue;
				try {
					all.addAll(Tlv.primitives(Apdu.data(r)));
				} catch (IllegalArgumentException malformed) {
					// One bad record must not cost the whole card; the PAN usually lives in another.
				}
			}
		}

		final String pan = extractPan(all);
		if (pan == null) return null;
		return new CardApp(aid, label(all, aid), formatPan(pan), digits(all, 0x5F34),
				text(all, 0x5F20), country(all), currency(all), ymFromBcd(all, 0x5F25),
				extractExpiry(all), hexOf(all, 0x9F08));
	}

	private static byte[] select(byte[] name) {
		final byte[] cmd = new byte[name.length + 6];
		cmd[1] = (byte) 0xA4;
		cmd[2] = 0x04;
		cmd[4] = (byte) name.length;
		System.arraycopy(name, 0, cmd, 5, name.length);
		return cmd; // trailing Le = 00
	}

	private static byte[] gpoCommand(byte[] pdol) {
		final byte[] arg = gpoArgument(pdol);
		final byte[] cmd = new byte[arg.length + 6];
		cmd[0] = (byte) 0x80;
		cmd[1] = (byte) 0xA8;
		cmd[4] = (byte) arg.length;
		System.arraycopy(arg, 0, cmd, 5, arg.length);
		return cmd; // trailing Le = 00
	}

	/** GPO command data: tag 83 wrapping the fields the PDOL asks for, zero unless known. */
	static byte[] gpoArgument(byte[] pdol) {
		final ByteArrayOutputStream fields = new ByteArrayOutputStream();
		for (int i = 0; i < pdol.length; ) {
			final int tagLen = Tlv.tagLength(pdol, i);
			int tag = 0;
			for (int k = 0; k < tagLen; ++k) tag = (tag << 8) | (pdol[i++] & 0xFF);
			if (i >= pdol.length) throw new IllegalArgumentException("malformed PDOL");
			final byte[] v = new byte[pdol[i++] & 0xFF];
			if ((tag == 0x9F1A || tag == 0x5F2A) && v.length >= 2) {
				// Terminal country / transaction currency: 0840 (US / USD).
				v[0] = 0x08;
				v[1] = 0x40;
			} else if (tag == 0x9F66 && v.length >= 4) {
				// Terminal transaction qualifiers.
				v[0] = (byte) 0xF6;
				v[1] = 0x20;
				v[2] = 0x40;
				v[3] = 0x00;
			}
			fields.write(v, 0, v.length);
		}
		final byte[] data = fields.toByteArray();
		final byte[] arg = new byte[data.length + 2];
		arg[0] = (byte) 0x83;
		arg[1] = (byte) data.length;
		System.arraycopy(data, 0, arg, 2, data.length);
		return arg;
	}

	/**
	 * The AFL from a GPO Format-1 response: {@code 80 <len> <AIP 2 bytes> <AFL>}.
	 * Handles long-form lengths: {@code 80 81 nn} used to be misread as length
	 * 0x81, which dropped the AFL and rejected a valid card.
	 *
	 * @return the AFL, or null if this is not Format-1 or carries no AFL
	 */
	static byte[] aflFromFormat1(byte[] gpoData) {
		if (gpoData.length < 2 || gpoData[0] != (byte) 0x80) return null;
		int p = 1;
		int len = gpoData[p++] & 0xFF;
		if (len >= 0x80) {
			final int count = len & 0x7F;
			len = 0;
			for (int k = 0; k < count && p < gpoData.length; ++k) {
				len = (len << 8) | (gpoData[p++] & 0xFF);
			}
		}
		final int valEnd = Math.min(p + len, gpoData.length);
		final int aflStart = p + 2; // skip the 2-byte AIP
		if (valEnd - aflStart < 4) return null;
		return Arrays.copyOfRange(gpoData, aflStart, valEnd);
	}

	static String extractPan(List<Tlv> all) {
		final Tlv t57 = Tlv.find(all, 0x57);
		if (t57 != null) {
			final String hex = Bytes.hex(t57.value);
			final int sep = hex.indexOf('D');
			if (sep > 0) return hex.substring(0, sep);
		}
		final Tlv t5a = Tlv.find(all, 0x5A);
		if (t5a != null) {
			final String hex = Bytes.hex(t5a.value);
			final int f = hex.indexOf('F');
			return (f > 0) ? hex.substring(0, f) : hex;
		}
		return null;
	}

	static String extractExpiry(List<Tlv> all) {
		final Tlv t57 = Tlv.find(all, 0x57);
		if (t57 != null) {
			final String hex = Bytes.hex(t57.value);
			final int sep = hex.indexOf('D');
			if (sep > 0 && hex.length() >= sep + 5) {
				return "20" + hex.substring(sep + 1, sep + 3) + "." + hex.substring(sep + 3, sep + 5);
			}
		}
		return ymFromBcd(all, 0x5F24);
	}

	static String ymFromBcd(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		if (t == null || t.value.length < 2) return null;
		return String.format(Locale.ROOT, "20%02X.%02X", t.value[0] & 0xFF, t.value[1] & 0xFF);
	}

	static String country(List<Tlv> all) {
		final Tlv t = Tlv.find(all, 0x5F28);
		if (t == null || t.value.length < 2) return null;
		return String.format(Locale.ROOT, "%02X%02X", t.value[0] & 0xFF, t.value[1] & 0xFF);
	}

	private static String text(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		return (t == null) ? null : new String(t.value, StandardCharsets.UTF_8).trim();
	}

	private static String hexOf(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		return (t == null) ? null : Bytes.hex(t.value);
	}

	/** BCD digits, skipping any nibble above 9 (filler). */
	private static String digits(List<Tlv> all, int tag) {
		final Tlv t = Tlv.find(all, tag);
		if (t == null) return null;
		final StringBuilder sb = new StringBuilder(t.value.length * 2);
		for (final byte v : t.value) {
			final int hi = (v >> 4) & 0xF;
			final int lo = v & 0xF;
			if (hi <= 9) sb.append((char) ('0' + hi));
			if (lo <= 9) sb.append((char) ('0' + lo));
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String label(List<Tlv> all, byte[] aid) {
		final String label = text(all, 0x50);
		if (label != null && !label.isEmpty()) return label;
		final String preferred = text(all, 0x9F12);
		if (preferred != null && !preferred.isEmpty()) return preferred;
		return identify(aid);
	}

	private static String formatPan(String digits) {
		final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 4);
		for (int i = 0; i < digits.length(); ++i) {
			if (i > 0 && i % 4 == 0) out.append(' ');
			out.append(digits.charAt(i));
		}
		return out.toString();
	}

	static Currency currency(List<Tlv> all) {
		Tlv t = Tlv.find(all, 0x9F42);
		if (t == null) t = Tlv.find(all, 0x5F2A);
		if (t == null || t.value.length < 2) return null;
		switch (((t.value[0] & 0xFF) << 8) | (t.value[1] & 0xFF)) {
			case 0x0840: return Currency.USD;
			case 0x0978: return Currency.EUR;
			case 0x0826: return Currency.GBP;
			case 0x0392: return Currency.JPY;
			case 0x0756: return Currency.CHF;
			case 0x0124: return Currency.CAD;
			case 0x0036: return Currency.AUD;
			case 0x0554: return Currency.NZD;
			case 0x0156: return Currency.CNY;
			case 0x0344: return Currency.HKD;
			case 0x0901: return Currency.TWD;
			case 0x0410: return Currency.KRW;
			case 0x0702: return Currency.SGD;
			case 0x0356: return Currency.INR;
			case 0x0360: return Currency.IDR;
			case 0x0764: return Currency.THB;
			case 0x0458: return Currency.MYR;
			case 0x0608: return Currency.PHP;
			case 0x0704: return Currency.VND;
			case 0x0643: return Currency.RUB;
			case 0x0949: return Currency.TRY;
			case 0x0784: return Currency.AED;
			case 0x0682: return Currency.SAR;
			case 0x0376: return Currency.ILS;
			case 0x0710: return Currency.ZAR;
			case 0x0986: return Currency.BRL;
			case 0x0484: return Currency.MXN;
			case 0x0860: return Currency.UZS;
			case 0x0398: return Currency.KZT;
			case 0x0417: return Currency.KGS;
			case 0x0944: return Currency.AZN;
			case 0x0981: return Currency.GEL;
			case 0x0051: return Currency.AMD;
			case 0x0933: return Currency.BYN;
			case 0x0980: return Currency.UAH;
			default: return null;
		}
	}

	static String identify(byte[] aid) {
		final String h = Bytes.hex(aid);
		if (h.startsWith("A0000000031010")) return "Visa";
		if (h.startsWith("A0000000041010")) return "Mastercard";
		if (h.startsWith("A000000333")) return "UnionPay";
		if (h.startsWith("A0000000651010")) return "JCB";
		if (h.startsWith("A0000000250000") || h.startsWith("A000000025010402")) return "American Express";
		if (h.startsWith("A0000001523010") || h.startsWith("A0000001524010")) return "Discover";
		if (h.startsWith("A0000000043060")) return "Maestro";
		if (h.startsWith("A0000005241010")) return "RuPay";
		if (h.startsWith("A0000006581010") || h.startsWith("A0000006582010")) return "HUMO";
		if (h.startsWith("A0000005942010")) return "UZCARD";
		return "Unknown";
	}
}
```

- [ ] **Step 10: Run the EMV tests**

Run: `./gradlew :card:testDebugUnitTest --tests '*EmvTest'`
Expected: PASS — 21 tests (19 moved + 2 new).

- [ ] **Step 11: Write the failing reader tests**

`card/src/test/java/com/adkhambek/reader/card/CardReaderTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/** SYNTHETIC transcripts: a one-application Visa card built to the EMV structure. */
public class CardReaderTest {

	private static final String SELECT_PPSE = "00A404000E325041592E5359532E444446303100";
	private static final String PPSE_FCI =
			"6F20 840E325041592E5359532E4444463031 A50E BF0C0B 6109 4F07A0000000031010 9000";
	private static final String SELECT_VISA = "00A4040007A000000003101000";
	private static final String VISA_FCI = "6F11 8407A0000000031010 A506 500456495341 9000";
	private static final String GPO_NO_PDOL = "80A8000002830000";
	/** PAN 4111…1111, expiry 5F24 = 27-12-31. */
	private static final String RECORD = "7010 5A084111111111111111 5F2403271231 9000";

	private static ReadException.Reason reasonOf(Transceiver card) {
		try {
			CardReader.readWith("04A1B2C3", card);
			fail("expected ReadException");
			return null;
		} catch (ReadException e) {
			return e.reason();
		}
	}

	@Test public void readsAnApplication() throws ReadException {
		final Replay card = new Replay()
				.expect(SELECT_PPSE).reply(PPSE_FCI)
				.expect(SELECT_VISA).reply(VISA_FCI)
				.expect(GPO_NO_PDOL).reply("8006 1800 08010100 9000")
				.expect("00B2010C00").reply(RECORD);

		final Card result = CardReader.readWith("04A1B2C3", card);

		card.assertExhausted();
		assertEquals("04A1B2C3", result.uid());
		assertEquals(1, result.apps().size());
		final CardApp app = result.apps().get(0);
		assertEquals("VISA", app.label());
		assertEquals("4111 1111 1111 1111", app.pan());
		assertEquals("2027.12", app.expiryDate());
	}

	/** Review focus: a malformed record is skipped; the PAN comes from the next one. */
	@Test public void malformedRecordIsSkipped() throws ReadException {
		final Replay card = new Replay()
				.expect(SELECT_PPSE).reply(PPSE_FCI)
				.expect(SELECT_VISA).reply(VISA_FCI)
				.expect(GPO_NO_PDOL).reply("8006 1800 08010200 9000")
				.expect("00B2010C00").reply("7004 5A084111 9000")
				.expect("00B2020C00").reply(RECORD);

		final Card result = CardReader.readWith("04A1B2C3", card);

		assertEquals("4111 1111 1111 1111", result.apps().get(0).pan());
	}

	@Test public void noPpseIsUnsupported() {
		final Replay card = new Replay().expect(SELECT_PPSE).reply("6A82");
		assertEquals(ReadException.Reason.UNSUPPORTED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void lostCardIsCardLost() {
		assertEquals(ReadException.Reason.CARD_LOST, reasonOf(apdu -> {
			throw new IOException("Tag was lost.");
		}));
	}
}
```

Run: `./gradlew :card:testDebugUnitTest --tests '*CardReaderTest'`
Expected: compilation FAILS — `CardReader` does not exist.

- [ ] **Step 12: Write `CardReader`**

`card/src/main/java/com/adkhambek/reader/card/CardReader.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.card;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Reads EMV contactless bank cards over NFC.
 *
 * <pre>{@code
 * CardReader reader = new CardReader();
 * Cancellable c = reader.read(tag, new Callback<Card>() {
 *     public void onSuccess(Card card) { ... }
 *     public void onError(ReadException e) { ... }
 * });
 * // onDestroy: c.cancel();
 * }</pre>
 */
public final class CardReader {
	private static final int TIMEOUT_MS = 5000;

	private final Executor work;
	private final Executor callbacks;

	/** Reads on a background thread shared by all card readers; callbacks on the main thread. */
	public CardReader() {
		this(Defaults.WORK, Defaults.MAIN);
	}

	public CardReader(Executor work, Executor callbacks) {
		if (work == null || callbacks == null) {
			throw new IllegalArgumentException("executors must be non-null");
		}
		this.work = work;
		this.callbacks = callbacks;
	}

	/** Blocking read. Call from a background thread. */
	@WorkerThread
	public Card read(Tag tag) throws ReadException {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		return read(tag, IsoDep.get(tag));
	}

	/**
	 * Reads on the work executor and reports once to {@code callback} on the
	 * callback executor, unless cancelled.
	 */
	public Cancellable read(Tag tag, Callback<Card> callback) {
		if (tag == null) throw new IllegalArgumentException("tag is null");
		if (callback == null) throw new IllegalArgumentException("callback is null");
		// One IsoDep instance for both the read and cancel(), so cancel closes
		// the connection the read is actually using.
		final IsoDep isoDep = IsoDep.get(tag);
		return Call.start(work, callbacks, callback, isoDep, () -> read(tag, isoDep));
	}

	private static Card read(Tag tag, IsoDep isoDep) throws ReadException {
		if (isoDep == null) {
			throw new ReadException(ReadException.Reason.UNSUPPORTED, "card is not ISO-DEP");
		}
		try {
			isoDep.connect();
			isoDep.setTimeout(TIMEOUT_MS);
			return readWith(Bytes.hex(tag.getId()), isoDep::transceive);
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
	static Card readWith(String uid, Transceiver transceiver) throws ReadException {
		try {
			final List<CardApp> apps = Emv.read(new Apdu(transceiver));
			if (apps == null) {
				throw new ReadException(ReadException.Reason.UNSUPPORTED,
						"no PPSE: not a contactless payment card");
			}
			return new Card(uid, apps);
		} catch (IOException e) {
			throw new ReadException(ReadException.Reason.CARD_LOST, "card lost: " + e.getMessage(), e);
		} catch (RuntimeException e) {
			throw new ReadException(ReadException.Reason.FAILED, e.toString(), e);
		}
	}

	/** Loaded on first use of the no-arg constructor only, so unit tests never touch Looper. */
	private static final class Defaults {
		static final Executor WORK = Executors.newSingleThreadExecutor(r -> {
			final Thread t = new Thread(r, "read3r-card");
			t.setDaemon(true);
			return t;
		});
		static final Executor MAIN = new Handler(Looper.getMainLooper())::post;
	}
}
```

- [ ] **Step 13: Run all `card` tests, then the whole build**

Run: `./gradlew :card:testDebugUnitTest`
Expected: PASS — 25 + 21 + 4 = 50 tests.

Run: `./gradlew test lint`
Expected: BUILD SUCCESSFUL. The old modules are untouched and still pass.

- [ ] **Step 14: Commit**

```bash
git add card settings.gradle.kts
git commit -m "feat(card): add the bank card library

Blocking read plus Executor + callback, one ReadException with a
Reason, and a private APDU/TLV helper set that :passport will copy."
```

---

### Task 3: `passport` library

**Files:**
- Create: `passport/build.gradle.kts`, `passport/gradle.properties`, `passport/src/main/AndroidManifest.xml`
- Create (twins, copied from `card`): `passport/src/main/java/com/adkhambek/reader/passport/{Transceiver,Apdu,Tlv,ReadException,Callback,Cancellable,Call}.java`, `passport/src/test/java/com/adkhambek/reader/passport/{Replay,ReplayTest,ApduTest,TlvTest,CallTest}.java`
- Create: `passport/src/main/java/com/adkhambek/reader/passport/{Bytes,MrzFormatException,MrzDocument,Mrz,MrzKey,PhotoFormat,Photo,PersonalDetails,DocumentDetails,Passport,Bac,Iso9797Mac,SecureMessaging,EfReader,DgParser,Dg2,PassportReader}.java`
- Create: `passport/src/test/java/com/adkhambek/reader/passport/{MrzTest,MrzKeyTest,BacTest,Dg2Test,SecureMessagingTest,EfReaderTest,PassportReaderTest}.java`
- Delete: `mrz/` (untracked; its `MrzTest.java` moves here)
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: the twin files from Task 2, copied.
- Produces (public): `PassportReader()`, `PassportReader(Executor, Executor)`, `@WorkerThread Passport read(Tag, MrzKey) throws ReadException`, `Cancellable read(Tag, MrzKey, Callback<Passport>)`; `Passport` with `mrz()`, `personalDetails()`, `documentDetails()`, `nationalData()`, `photo()`, `presentDataGroups()`; `record PersonalDetails(String fullName, String otherNames, String placeOfBirth, String fullDateOfBirth, String address, String telephone, String profession, String title)`; `record DocumentDetails(String issuingAuthority, String dateOfIssue, String endorsements)`; `record Photo(byte[] bytes, PhotoFormat format)`; `enum PhotoFormat { JPEG, JP2, UNKNOWN }`; `MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry)` with public final fields of the same names; `Mrz.isValid(String)`, `Mrz.decode(String) throws MrzFormatException`; `MrzDocument` with `documentType()`, `issuingCountry()`, `documentNumber()`, `lastName()`, `firstName()`, `sex()`, `nationality()`, `dateOfBirth()`, `dateOfExpiry()`, `personalNumber()`, `optionalData()`, `raw()`, `hasMrz()`; `MrzFormatException extends IllegalArgumentException`.
- Produces (package, used by Task 4): `EfReader(Transceiver sm)`, `byte[] read(int fid) throws IOException` (null for 6A82); `SecureMessaging.transceive(Apdu, byte[])`; `static Passport PassportReader.readWith(Transceiver, MrzKey)`; constants `PassportReader.FID_COM`, `FID_DG1`, `FID_DG2`, `FID_DG11`, `FID_DG12`, `FID_DG13`; `Passport.Builder` with fields `mrz` (`MrzDocument.Builder`), `personalDetails`, `documentDetails`, `nationalData`, `photo`, `presentDataGroups`.

- [ ] **Step 1: Create the module and copy the twins**

```bash
mkdir -p passport/src/main/java/com/adkhambek/reader/passport passport/src/test/java/com/adkhambek/reader/passport
for f in Transceiver Apdu Tlv ReadException Callback Cancellable Call; do
  cp card/src/main/java/com/adkhambek/reader/card/$f.java passport/src/main/java/com/adkhambek/reader/passport/$f.java
done
for f in Replay ReplayTest ApduTest TlvTest CallTest; do
  cp card/src/test/java/com/adkhambek/reader/card/$f.java passport/src/test/java/com/adkhambek/reader/passport/$f.java
done
perl -pi -e 's/^package com\.adkhambek\.reader\.card;/package com.adkhambek.reader.passport;/' \
   passport/src/main/java/com/adkhambek/reader/passport/*.java passport/src/test/java/com/adkhambek/reader/passport/*.java
```

`passport/build.gradle.kts`:

```kotlin
plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.passport"
}

dependencies {
    // @WorkerThread on the blocking read. CLASS retention: consumers do not
    // need it on their compile classpath, so implementation is enough.
    implementation(libs.androidx.annotation)
}
```

`passport/gradle.properties`:

```properties
POM_ARTIFACT_ID=passport
POM_NAME=Read3r Passport
POM_DESCRIPTION=Reads ICAO 9303 ePassports and eMRTD ID cards over Android NFC (BAC, Secure Messaging, DG1/DG2/DG11-13), plus an MRZ decoder and check-digit validator. Java API: blocking read, or Executor + callback.
```

`passport/src/main/AndroidManifest.xml`: identical to `card/src/main/AndroidManifest.xml`.

In `settings.gradle.kts`, add `    ":passport",` after `    ":nfc",`.

Run: `./gradlew :passport:testDebugUnitTest`
Expected: PASS — 25 twin tests.

- [ ] **Step 2: Move the MRZ test and add the review-focus tests (failing)**

```bash
cp mrz/src/test/java/com/adkhambek/reader/mrz/MrzTest.java passport/src/test/java/com/adkhambek/reader/passport/MrzTest.java
perl -pi -e 's/^package .*/package com.adkhambek.reader.passport;/' passport/src/test/java/com/adkhambek/reader/passport/MrzTest.java
rm -r mrz
```

Add to `MrzTest`, before the `bump` helper:

```java
	// --- Whitespace: MRZs scanned from QR codes carry line breaks ----------

	/** Review focus: whitespace is never an MRZ character, so it is ignored. */
	@Test public void isValid_ignoresLineBreaks() {
		assertTrue(Mrz.isValid(TD3.substring(0, 44) + "\n" + TD3.substring(44) + "\n"));
	}

	@Test public void decode_ignoresLineBreaks() {
		assertEquals("L898902C3",
				Mrz.decode(TD3.substring(0, 44) + "\r\n" + TD3.substring(44)).documentNumber());
	}
```

`passport/src/test/java/com/adkhambek/reader/passport/MrzKeyTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Review focus: input BAC cannot encode is rejected before any card I/O. */
public class MrzKeyTest {

	private static void assertRejected(String doc, String dob, String exp) {
		try {
			new MrzKey(doc, dob, exp);
			fail("expected IllegalArgumentException for " + doc + "/" + dob + "/" + exp);
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test public void upperCasesAndTrims() {
		final MrzKey key = new MrzKey(" l898902c< ", "690806", "940623");
		assertEquals("L898902C<", key.documentNumber);
	}

	@Test public void rejectsPunctuationAndInnerSpaces() {
		assertRejected("AB-123", "690806", "940623");
		assertRejected("AB 123", "690806", "940623");
	}

	@Test public void rejectsDatesThatAreNotSixDigits() {
		assertRejected("L898902C<", "69-08-06", "940623");
		assertRejected("L898902C<", "690806", "9406");
	}

	@Test public void rejectsNulls() {
		assertRejected(null, "690806", "940623");
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest`
Expected: compilation FAILS — `Mrz`, `MrzDocument`, `MrzFormatException`, `MrzKey` do not exist.

- [ ] **Step 3: Write the MRZ types**

`passport/src/main/java/com/adkhambek/reader/passport/MrzFormatException.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The input is not a machine-readable zone this library can decode. Unchecked:
 * gate untrusted input on {@link Mrz#isValid} and this never happens.
 */
public final class MrzFormatException extends IllegalArgumentException {
	public MrzFormatException(String message) {
		super(message);
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/MrzDocument.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The fields of an ICAO 9303 machine-readable zone — immutable.
 *
 * <p>Absent fields are null. Dates are {@code YYYY-MM-DD}, or the raw
 * {@code YYMMDD} field when it was not a valid date.
 */
public final class MrzDocument {

	private final String documentType;
	private final String issuingCountry;
	private final String documentNumber;
	private final String lastName;
	private final String firstName;
	private final String sex;
	private final String nationality;
	private final String dateOfBirth;
	private final String dateOfExpiry;
	private final String personalNumber;
	private final String optionalData;
	private final String raw;

	private MrzDocument(Builder b) {
		this.documentType = b.documentType;
		this.issuingCountry = b.issuingCountry;
		this.documentNumber = b.documentNumber;
		this.lastName = b.lastName;
		this.firstName = b.firstName;
		this.sex = b.sex;
		this.nationality = b.nationality;
		this.dateOfBirth = b.dateOfBirth;
		this.dateOfExpiry = b.dateOfExpiry;
		this.personalNumber = b.personalNumber;
		this.optionalData = b.optionalData;
		this.raw = b.raw;
	}

	public String documentType() { return documentType; }

	public String issuingCountry() { return issuingCountry; }

	public String documentNumber() { return documentNumber; }

	public String lastName() { return lastName; }

	public String firstName() { return firstName; }

	public String sex() { return sex; }

	public String nationality() { return nationality; }

	public String dateOfBirth() { return dateOfBirth; }

	public String dateOfExpiry() { return dateOfExpiry; }

	public String personalNumber() { return personalNumber; }

	public String optionalData() { return optionalData; }

	/** The MRZ as decoded, with whitespace removed. */
	public String raw() { return raw; }

	/** True when a document number was decoded — i.e. this really is an MRZ. */
	public boolean hasMrz() { return documentNumber != null; }

	@Override
	public String toString() {
		// Redacted: an MRZ is high-value identity data and toString() is what lands in logs.
		return "MrzDocument[" + documentType + " " + issuingCountry
				+ (documentNumber == null ? "" : " ****") + "]";
	}

	/** Filled by {@link Mrz} and the DG11 parser; not public API. */
	static final class Builder {
		private String documentType;
		private String issuingCountry;
		private String documentNumber;
		private String lastName;
		private String firstName;
		private String sex;
		private String nationality;
		private String dateOfBirth;
		private String dateOfExpiry;
		private String personalNumber;
		private String optionalData;
		private String raw;

		Builder documentType(String v) { this.documentType = v; return this; }

		Builder issuingCountry(String v) { this.issuingCountry = v; return this; }

		Builder documentNumber(String v) { this.documentNumber = v; return this; }

		Builder lastName(String v) { this.lastName = v; return this; }

		Builder firstName(String v) { this.firstName = v; return this; }

		Builder sex(String v) { this.sex = v; return this; }

		Builder nationality(String v) { this.nationality = v; return this; }

		Builder dateOfBirth(String v) { this.dateOfBirth = v; return this; }

		Builder dateOfExpiry(String v) { this.dateOfExpiry = v; return this; }

		Builder personalNumber(String v) { this.personalNumber = v; return this; }

		/** DG11's 5F10 is a fallback for a blank MRZ field; it must not overwrite DG1. */
		Builder personalNumberIfAbsent(String v) {
			if (this.personalNumber == null) this.personalNumber = v;
			return this;
		}

		Builder optionalData(String v) { this.optionalData = v; return this; }

		Builder raw(String v) { this.raw = v; return this; }

		MrzDocument build() { return new MrzDocument(this); }
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/Mrz.java` — `common/…/mrz/Mrz.java` with `IdCard` replaced by `MrzDocument`, `isValidMrz` renamed `isValid`, `decodeMrz` renamed `decodeInto` and made package-private, a throwing `decode`, and whitespace removal:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.util.Locale;

/**
 * Decoder and check-digit validator for an ICAO 9303 machine-readable zone
 * (TD1: 3×30 = 90 characters, TD2: 2×36 = 72, TD3: 2×44 = 88).
 *
 * <p>Whitespace is ignored everywhere, so an MRZ scanned from a QR code with
 * line breaks between its lines decodes as is.
 */
public final class Mrz {
	private Mrz() {
	}

	/**
	 * Verify the check digits of the document number, date of birth and date of
	 * expiry. A random string of the right length decodes without error, so gate
	 * untrusted input (a scanned QR code) on this before calling {@link #decode}.
	 *
	 * @return false for null, an unsupported length, illegal characters, or a bad check digit
	 */
	public static boolean isValid(String mrz) {
		if (mrz == null) return false;
		final String m = compact(mrz);
		switch (m.length()) {
			case 90: return validateTd1(m);
			case 72: return validateTd2(m);
			case 88: return validateTd3(m);
			default: return false;
		}
	}

	/**
	 * Split an MRZ into its fields. Does not check check digits — see {@link #isValid}.
	 *
	 * @throws MrzFormatException if the length matches no supported format
	 */
	public static MrzDocument decode(String mrz) throws MrzFormatException {
		if (mrz == null) throw new MrzFormatException("mrz is null");
		final String m = compact(mrz);
		if (m.length() != 90 && m.length() != 72 && m.length() != 88) {
			throw new MrzFormatException("unsupported MRZ length: " + m.length());
		}
		final MrzDocument.Builder b = new MrzDocument.Builder();
		decodeInto(m, b);
		return b.build();
	}

	/** Decode into an existing builder; the eMRTD reader merges DG1 with other groups. */
	static void decodeInto(String mrz, MrzDocument.Builder out) {
		final String m = compact(mrz);
		out.raw(m);
		switch (m.length()) {
			case 90: decodeTd1(m, out); break;
			case 72: decodeTd2(m, out); break;
			case 88: decodeTd3(m, out); break;
			default: // not an MRZ: leave everything but raw unset
		}
	}

	private static String compact(String s) {
		final StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (!Character.isWhitespace(c)) sb.append(c);
		}
		return sb.toString();
	}

	private static boolean validateTd1(String m) {
		// Document number line1[5..14) check at 14; DOB line2[0..6) check at 36;
		// expiry line2[8..14) check at 44.
		return checkDigit(m, 5, 9, 14)
				&& checkDigit(m, 30, 6, 36)
				&& checkDigit(m, 38, 6, 44);
	}

	private static boolean validateTd2(String m) {
		// Document number line2[0..9) check at 45; DOB check at 55; expiry check at 63.
		return checkDigit(m, 36, 9, 45)
				&& checkDigit(m, 49, 6, 55)
				&& checkDigit(m, 57, 6, 63);
	}

	private static boolean validateTd3(String m) {
		// Document number line2[0..9) check at 53; DOB check at 63; expiry check at 71.
		return checkDigit(m, 44, 9, 53)
				&& checkDigit(m, 57, 6, 63)
				&& checkDigit(m, 65, 6, 71);
	}

	/** ICAO 9303 mod-10 with weights 7-3-1. '<' → 0, '0'-'9' → 0-9, 'A'-'Z' → 10-35. */
	private static boolean checkDigit(String s, int start, int len, int checkPos) {
		if (checkPos >= s.length()) return false;
		final int[] weights = {7, 3, 1};
		int sum = 0;
		for (int i = 0; i < len; ++i) {
			final char c = s.charAt(start + i);
			final int v;
			if (c >= '0' && c <= '9') v = c - '0';
			else if (c >= 'A' && c <= 'Z') v = c - 'A' + 10;
			else if (c == '<') v = 0;
			else return false;
			sum += v * weights[i % 3];
		}
		final int expect = sum % 10;
		final char ck = s.charAt(checkPos);
		if (ck == '<') return expect == 0;
		if (ck < '0' || ck > '9') return false;
		return expect == (ck - '0');
	}

	private static void decodeTd1(String m, MrzDocument.Builder out) {
		final String l1 = m.substring(0, 30);
		final String l2 = m.substring(30, 60);
		final String l3 = m.substring(60, 90);
		final String[] names = splitName(l3);

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l1.substring(5, 14)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(7)))
				.nationality(clean(l2.substring(15, 18)))
				.dateOfBirth(formatDate(l2.substring(0, 6), false))
				.dateOfExpiry(formatDate(l2.substring(8, 14), true));

		final String optional1 = clean(l1.substring(15, 30));
		if (!optional1.isEmpty()) out.optionalData(optional1);
	}

	private static void decodeTd2(String m, MrzDocument.Builder out) {
		final String l1 = m.substring(0, 36);
		final String l2 = m.substring(36, 72);
		final String[] names = splitName(l1.substring(5, 36));

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l2.substring(0, 9)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(20)))
				.nationality(clean(l2.substring(10, 13)))
				.dateOfBirth(formatDate(l2.substring(13, 19), false))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true));
	}

	private static void decodeTd3(String m, MrzDocument.Builder out) {
		final String l1 = m.substring(0, 44);
		final String l2 = m.substring(44, 88);
		final String[] names = splitName(l1.substring(5, 44));

		out.documentType(clean(l1.substring(0, 2)))
				.issuingCountry(clean(l1.substring(2, 5)))
				.documentNumber(clean(l2.substring(0, 9)))
				.lastName(names[0])
				.firstName(names[1])
				.sex(decodeSex(l2.charAt(20)))
				.nationality(clean(l2.substring(10, 13)))
				.dateOfBirth(formatDate(l2.substring(13, 19), false))
				.dateOfExpiry(formatDate(l2.substring(21, 27), true));

		final String personalNr = clean(l2.substring(28, 42));
		if (!personalNr.isEmpty()) out.personalNumber(personalNr);
	}

	private static String[] splitName(String namePart) {
		final int sep = namePart.indexOf("<<");
		final String surname = (sep >= 0) ? namePart.substring(0, sep) : namePart;
		final String given = (sep >= 0) ? namePart.substring(sep + 2) : "";
		return new String[]{clean(surname), clean(given)};
	}

	private static String decodeSex(char c) {
		switch (c) {
			case 'M': return "M";
			case 'F': return "F";
			default: return null;
		}
	}

	/** {@code YYMMDD} → {@code YYYY-MM-DD}. Birth years ≥ 50 land in the 1900s; expiry always 2000s. */
	private static String formatDate(String yymmdd, boolean isExpiry) {
		if (yymmdd.length() != 6) return yymmdd;
		try {
			final int yy = Integer.parseInt(yymmdd.substring(0, 2));
			final int mm = Integer.parseInt(yymmdd.substring(2, 4));
			final int dd = Integer.parseInt(yymmdd.substring(4, 6));
			// Reject impossible months/days rather than emitting "1974-99-99".
			if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return yymmdd;
			final int yyyy = (!isExpiry && yy >= 50) ? yy + 1900 : yy + 2000;
			return String.format(Locale.ROOT, "%04d-%02d-%02d", yyyy, mm, dd);
		} catch (NumberFormatException e) {
			return yymmdd;
		}
	}

	private static String clean(String s) {
		return s.replace('<', ' ').trim();
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/MrzKey.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.util.Locale;

/**
 * The three MRZ fields Basic Access Control derives its keys from. Printed on
 * the document's data page.
 */
public final class MrzKey {
	public final String documentNumber;
	public final String dateOfBirth;
	public final String dateOfExpiry;

	/**
	 * @param documentNumber A–Z, 0–9 or {@code <}; lower case is accepted and upper-cased
	 * @param dateOfBirth    {@code YYMMDD}
	 * @param dateOfExpiry   {@code YYMMDD}
	 * @throws IllegalArgumentException if a field could not appear in an MRZ
	 */
	public MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry) {
		if (documentNumber == null || dateOfBirth == null || dateOfExpiry == null) {
			throw new IllegalArgumentException("MRZ key fields must be non-null");
		}
		final String doc = documentNumber.trim().toUpperCase(Locale.ROOT);
		final String dob = dateOfBirth.trim();
		final String exp = dateOfExpiry.trim();
		if (!doc.matches("[A-Z0-9<]+")) {
			throw new IllegalArgumentException("document number may contain only A-Z, 0-9 and <");
		}
		if (!dob.matches("[0-9]{6}")) throw new IllegalArgumentException("dateOfBirth must be YYMMDD");
		if (!exp.matches("[0-9]{6}")) throw new IllegalArgumentException("dateOfExpiry must be YYMMDD");
		this.documentNumber = doc;
		this.dateOfBirth = dob;
		this.dateOfExpiry = exp;
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest --tests '*MrzTest' --tests '*MrzKeyTest'`
Expected: PASS — 16 + 4 = 20 tests.

- [ ] **Step 4: Port the crypto tests and write the SM tests (failing)**

```bash
cp nfc/src/test/java/com/adkhambek/reader/nfc/id/bac/BacTest.java passport/src/test/java/com/adkhambek/reader/passport/BacTest.java
cp nfc/src/test/java/com/adkhambek/reader/nfc/id/dg/Dg2Test.java passport/src/test/java/com/adkhambek/reader/passport/Dg2Test.java
perl -pi -e 's/^package .*/package com.adkhambek.reader.passport;/; s/^import com\.adkhambek\.reader\.(iso7816\.Hex|common\.mrz\.PhotoFormat);\n//' \
   passport/src/test/java/com/adkhambek/reader/passport/{BacTest,Dg2Test}.java
perl -pi -e 's/Hex\.pad\(([^,]+), 8\)/Bytes.pad($1)/g; s/Hex\.unpad\(/Bytes.unpad(/g' \
   passport/src/test/java/com/adkhambek/reader/passport/BacTest.java
```

`passport/src/test/java/com/adkhambek/reader/passport/SecureMessagingTest.java`. **Before running it, check every hex string in the four D.4 tests against ICAO 9303-11 Appendix D.4.** They are transcribed values; a wrong digit would look exactly like a real bug.

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** D.4 tests: ICAO 9303-11 Appendix D.4, Secure Messaging after the D.3 BAC example. */
public class SecureMessagingTest {

	private static final byte[] KS_ENC = Replay.hex("979EC13B1CBFE9DCD01AB0FED307EAE5");
	private static final byte[] KS_MAC = Replay.hex("F1CB1F1FB5ADF208806B89DC579DC1F8");
	private static final byte[] SSC = Replay.hex("887022120C06C226");

	private static final String SELECT_COM = "00A4020C02011E";
	private static final String SELECT_COM_RESPONSE = "990290008E08FA855A5D4C50A8ED";

	private static SecureMessaging sm() {
		return new SecureMessaging(KS_ENC, KS_MAC, SSC);
	}

	@Test public void d4_wrapsSelectEfCom() {
		assertEquals("0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800",
				Bytes.hex(sm().wrap(Replay.hex(SELECT_COM))));
	}

	@Test public void d4_unwrapsSelectResponse() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		assertEquals("9000", Bytes.hex(sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000)));
	}

	@Test public void d4_wrapsReadBinaryAfterSelect() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000);
		assertEquals("0CB000000D9701048E08ED6705417E96BA5500",
				Bytes.hex(sm.wrap(Replay.hex("00B0000004"))));
	}

	@Test public void d4_unwrapsReadBinaryResponse() {
		final SecureMessaging sm = sm();
		sm.wrap(Replay.hex(SELECT_COM));
		sm.unwrap(Replay.hex(SELECT_COM_RESPONSE), 0x9000);
		sm.wrap(Replay.hex("00B0000004"));
		assertEquals("60145F019000", Bytes.hex(sm.unwrap(
				Replay.hex("8709019FF0EC34F9922651990290008E08AD55CC17140B2DED"), 0x9000)));
	}

	/** Case 2E: Le 2048 → extended Lc 000E (DO97 4 + DO8E 10), extended Le 0000. */
	@Test public void extendedLeProducesAnExtendedApdu() {
		final byte[] out = sm().wrap(Replay.hex("00B00000000800"));
		assertTrue(Bytes.hex(out).startsWith("0CB0000000000E97020800"));
		assertTrue(Bytes.hex(out).endsWith("0000"));
		assertEquals(4 + 3 + 14 + 2, out.length);
	}

	/** Review focus: a bare error status with no SM objects is passed through, not a MAC failure. */
	@Test public void unprotectedErrorStatusPassesThrough() {
		assertArrayEquals(Replay.hex("6A82"), sm().unwrap(new byte[0], 0x6A82));
	}

	@Test public void tlvEncodesLongFormLengths() {
		assertEquals("8E0101", Bytes.hex(SecureMessaging.tlv(0x8E, Replay.hex("01"))));
		assertEquals("8781C8", Bytes.hex(SecureMessaging.tlv(0x87, new byte[200])).substring(0, 6));
		assertEquals("8782012C", Bytes.hex(SecureMessaging.tlv(0x87, new byte[300])).substring(0, 8));
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest`
Expected: compilation FAILS — `Bytes`, `Bac`, `Iso9797Mac`, `Dg2`, `SecureMessaging` do not exist.

- [ ] **Step 5: Write `Bytes`, `Bac`, `Iso9797Mac`**

`passport/src/main/java/com/adkhambek/reader/passport/Bytes.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

final class Bytes {
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private Bytes() {
	}

	/** Upper-case hex, two characters per byte. */
	static String hex(byte[] b) {
		final char[] out = new char[b.length * 2];
		for (int i = 0; i < b.length; ++i) {
			out[2 * i] = HEX[(b[i] >> 4) & 0xF];
			out[2 * i + 1] = HEX[b[i] & 0xF];
		}
		return new String(out);
	}

	static byte[] concat(byte[]... parts) {
		int n = 0;
		for (final byte[] p : parts) n += p.length;
		final byte[] out = new byte[n];
		int o = 0;
		for (final byte[] p : parts) {
			System.arraycopy(p, 0, out, o, p.length);
			o += p.length;
		}
		return out;
	}

	static byte[] xor(byte[] a, byte[] b) {
		final byte[] out = new byte[a.length];
		for (int i = 0; i < a.length; ++i) out[i] = (byte) (a[i] ^ b[i]);
		return out;
	}

	/** ISO 7816-4 padding to 8-byte blocks: 0x80, then zeros. Always adds at least one byte. */
	static byte[] pad(byte[] data) {
		final byte[] out = new byte[(data.length / 8 + 1) * 8];
		System.arraycopy(data, 0, out, 0, data.length);
		out[data.length] = (byte) 0x80;
		return out;
	}

	/** Inverse of {@link #pad}: strip trailing zeros, then one 0x80. */
	static byte[] unpad(byte[] data) {
		int i = data.length - 1;
		while (i >= 0 && data[i] == 0) --i;
		if (i < 0 || data[i] != (byte) 0x80) throw new IllegalArgumentException("bad padding");
		final byte[] out = new byte[i];
		System.arraycopy(data, 0, out, 0, i);
		return out;
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/Iso9797Mac.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import android.annotation.SuppressLint;

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
```

`passport/src/main/java/com/adkhambek/reader/passport/Bac.java`:

```java
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
```

Note on `deriveKey`'s parity: the old loop set bit 0 so the byte has odd parity — `1 ^ parity(bits 1..7)`. `Integer.bitCount(b) & 1` is the parity of bits 1..7 (bit 0 is cleared in `b`), so `(bitCount & 1) ^ 1` is the same bit. `BacTest.deriveKey_*` pins it to the ICAO vectors.

- [ ] **Step 6: Write `SecureMessaging`**

`passport/src/main/java/com/adkhambek/reader/passport/SecureMessaging.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * ICAO 9303 Secure Messaging over 3DES + retail MAC.
 *
 * <p>Command: CLA |= 0x0C; DO87 = 01 ‖ encrypted padded data; DO97 = Le;
 * DO8E = MAC(SSC ‖ padded header ‖ DO87 ‖ DO97). Response: DO87, DO99 (the real
 * status word), DO8E. The SSC is incremented before each MAC, on both sides.
 */
final class SecureMessaging {
	private final byte[] ksEnc;
	private final byte[] ksMac;
	private final byte[] ssc;

	SecureMessaging(Bac bac) {
		this(bac.ksEnc, bac.ksMac, bac.ssc);
	}

	SecureMessaging(byte[] ksEnc, byte[] ksMac, byte[] ssc) {
		this.ksEnc = ksEnc;
		this.ksMac = ksMac;
		this.ssc = ssc.clone();
	}

	/** Wrap {@code plain}, send it, return the unwrapped {@code data ‖ SW}. */
	byte[] transceive(Apdu apdu, byte[] plain) throws IOException {
		final byte[] r = apdu.send(wrap(plain));
		return unwrap(Apdu.data(r), Apdu.sw(r));
	}

	byte[] wrap(byte[] plain) {
		final Command a = Command.parse(plain);
		final byte cla = (byte) (a.cla | 0x0C);
		final byte[] paddedHeader = Bytes.pad(new byte[]{cla, a.ins, a.p1, a.p2});

		byte[] do87 = new byte[0];
		if (a.data.length > 0) {
			do87 = tlv(0x87, Bytes.concat(new byte[]{0x01}, Bac.des3(ksEnc, Bytes.pad(a.data), true)));
		}
		byte[] do97 = new byte[0];
		if (a.le >= 0) {
			// Short: 256 encodes as 00. Extended: 65536 encodes as 0000.
			do97 = tlv(0x97, a.extended
					? new byte[]{(byte) (a.le >> 8), (byte) a.le}
					: new byte[]{(byte) a.le});
		}

		increment(ssc);
		final byte[] mac = Iso9797Mac.mac(ksMac, Bytes.concat(ssc, paddedHeader, do87, do97));
		final byte[] body = Bytes.concat(do87, do97, tlv(0x8E, mac));

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(cla);
		out.write(a.ins);
		out.write(a.p1);
		out.write(a.p2);
		if (a.extended || body.length > 0xFF) {
			out.write(0x00);
			out.write(body.length >> 8);
			out.write(body.length);
			out.write(body, 0, body.length);
			out.write(0x00);
			out.write(0x00);
		} else {
			out.write(body.length);
			out.write(body, 0, body.length);
			out.write(0x00);
		}
		return out.toByteArray();
	}

	byte[] unwrap(byte[] body, int outerSw) {
		// A chip may answer an error with a bare status word and no SM objects —
		// 6A82 for a file that does not exist. There is nothing to verify; pass it on.
		if (body.length == 0 && outerSw != Apdu.SW_OK) {
			return new byte[]{(byte) (outerSw >> 8), (byte) outerSw};
		}

		byte[] do87 = null;
		byte[] do99 = null;
		byte[] do8e = null;
		int pos = 0;
		while (pos < body.length) {
			final int tag = body[pos++] & 0xFF;
			int len = body[pos++] & 0xFF;
			if (len >= 0x80) {
				final int n = len & 0x7F;
				len = 0;
				for (int i = 0; i < n; ++i) len = (len << 8) | (body[pos++] & 0xFF);
			}
			final byte[] val = Arrays.copyOfRange(body, pos, pos + len);
			pos += len;
			switch (tag) {
				case 0x87: do87 = val; break;
				case 0x99: do99 = val; break;
				case 0x8E: do8e = val; break;
				default: // not part of the BAC SM profile; ignored
			}
		}

		increment(ssc);
		final byte[] expected = Iso9797Mac.mac(ksMac, Bytes.concat(ssc,
				do87 == null ? new byte[0] : tlv(0x87, do87),
				do99 == null ? new byte[0] : tlv(0x99, do99)));
		if (do8e == null || !MessageDigest.isEqual(expected, do8e)) {
			throw new IllegalStateException("SM MAC verification failed");
		}

		byte[] data = new byte[0];
		if (do87 != null) {
			// Drop the padding-content indicator (01), decrypt, unpad.
			data = Bytes.unpad(Bac.des3(ksEnc, Arrays.copyOfRange(do87, 1, do87.length), false));
		}
		final int sw = (do99 != null && do99.length == 2)
				? ((do99[0] & 0xFF) << 8) | (do99[1] & 0xFF)
				: outerSw;
		return Bytes.concat(data, new byte[]{(byte) (sw >> 8), (byte) sw});
	}

	/** One-byte tag, BER length (short, 81 or 82 form), value. */
	static byte[] tlv(int tag, byte[] value) {
		final int n = value.length;
		final byte[] len = (n < 0x80) ? new byte[]{(byte) n}
				: (n <= 0xFF) ? new byte[]{(byte) 0x81, (byte) n}
				: new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
		return Bytes.concat(new byte[]{(byte) tag}, len, value);
	}

	private static void increment(byte[] counter) {
		for (int i = counter.length - 1; i >= 0; --i) {
			if (++counter[i] != 0) return;
		}
	}

	/** A plain command APDU, ISO 7816-4 cases 1, 2S, 3S, 4S, 2E, 3E, 4E. {@code le} is -1 when absent. */
	private static final class Command {
		byte cla;
		byte ins;
		byte p1;
		byte p2;
		byte[] data = new byte[0];
		int le = -1;
		boolean extended;

		static Command parse(byte[] b) {
			final Command a = new Command();
			a.cla = b[0];
			a.ins = b[1];
			a.p1 = b[2];
			a.p2 = b[3];
			final int n = b.length;
			if (n == 4) return a;                                     // case 1
			if (n == 5) {                                             // case 2S
				a.le = orMax(b[4] & 0xFF, 256);
				return a;
			}
			if (b[4] != 0) {                                          // cases 3S, 4S
				final int lc = b[4] & 0xFF;
				a.data = Arrays.copyOfRange(b, 5, 5 + lc);
				if (n == 6 + lc) a.le = orMax(b[5 + lc] & 0xFF, 256);
				return a;
			}
			a.extended = true;
			if (n == 7) {                                             // case 2E
				a.le = orMax(u16(b, 5), 65536);
				return a;
			}
			final int lc = u16(b, 5);                                 // cases 3E, 4E
			a.data = Arrays.copyOfRange(b, 7, 7 + lc);
			if (n == 9 + lc) a.le = orMax(u16(b, 7 + lc), 65536);
			return a;
		}

		private static int u16(byte[] b, int i) {
			return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
		}

		private static int orMax(int v, int max) {
			return v == 0 ? max : v;
		}
	}
}
```

- [ ] **Step 7: Write the image and data-group parsers**

`passport/src/main/java/com/adkhambek/reader/passport/PhotoFormat.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** Encoding of the DG2 face image. JPEG 2000 is common and Android cannot decode it natively. */
public enum PhotoFormat {
	JPEG, JP2, UNKNOWN,
}
```

`passport/src/main/java/com/adkhambek/reader/passport/Dg2.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.util.Arrays;
import java.util.List;

/**
 * DG2 — the facial image, inside a biometric data block (5F2E, or 7F2E). Found
 * by its JPEG / JPEG 2000 magic bytes rather than by parsing CBEFF headers.
 */
final class Dg2 {
	private Dg2() {
	}

	static final class Image {
		final PhotoFormat format;
		final byte[] data;

		Image(PhotoFormat format, byte[] data) {
			this.format = format;
			this.data = data;
		}
	}

	static Image extract(byte[] dg2) {
		byte[] data = dg2;
		try {
			final List<Tlv> tlvs = Tlv.primitives(dg2);
			Tlv bdb = Tlv.find(tlvs, 0x5F2E);
			if (bdb == null) bdb = Tlv.find(tlvs, 0x7F2E);
			if (bdb != null) data = bdb.value;
		} catch (RuntimeException malformed) {
			// 7F2E is constructed in BER, so the walk descends into the raw image
			// bytes and throws. Scan the whole record for the image instead of
			// failing the read and losing DG1 / DG11-13.
			data = dg2;
		}
		return scanForImage(data);
	}

	private static Image scanForImage(byte[] d) {
		for (int i = 0; i < d.length; ++i) {
			// JPEG SOI: FF D8 FF
			if (i + 2 < d.length && (d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8
					&& (d[i + 2] & 0xFF) == 0xFF) {
				return new Image(PhotoFormat.JPEG, Arrays.copyOfRange(d, i, d.length));
			}
			// JP2 signature box: 00 00 00 0C 6A 50
			if (i + 5 < d.length && d[i] == 0x00 && d[i + 1] == 0x00 && d[i + 2] == 0x00
					&& d[i + 3] == 0x0C && (d[i + 4] & 0xFF) == 0x6A && (d[i + 5] & 0xFF) == 0x50) {
				return new Image(PhotoFormat.JP2, Arrays.copyOfRange(d, i, d.length));
			}
			// JP2 codestream: FF 4F FF 51
			if (i + 3 < d.length && (d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0x4F
					&& (d[i + 2] & 0xFF) == 0xFF && (d[i + 3] & 0xFF) == 0x51) {
				return new Image(PhotoFormat.JP2, Arrays.copyOfRange(d, i, d.length));
			}
		}
		return new Image(PhotoFormat.UNKNOWN, null);
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/Photo.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/**
 * The DG2 face image. {@code bytes} is not copied — treat it as read-only.
 * Biometric data: do not log or persist it casually.
 */
public record Photo(byte[] bytes, PhotoFormat format) {
}
```

`passport/src/main/java/com/adkhambek/reader/passport/PersonalDetails.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** DG11 — additional personal details. Any field may be null. */
public record PersonalDetails(
		String fullName,
		String otherNames,
		String placeOfBirth,
		String fullDateOfBirth,
		String address,
		String telephone,
		String profession,
		String title
) {
}
```

`passport/src/main/java/com/adkhambek/reader/passport/DocumentDetails.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

/** DG12 — additional document details. Any field may be null. */
public record DocumentDetails(String issuingAuthority, String dateOfIssue, String endorsements) {
}
```

`passport/src/main/java/com/adkhambek/reader/passport/Passport.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One eMRTD read — immutable. Optional data groups that were absent or unreadable are null. */
public final class Passport {
	private final MrzDocument mrz;
	private final PersonalDetails personalDetails;
	private final DocumentDetails documentDetails;
	private final Map<String, String> nationalData;
	private final Photo photo;
	private final List<String> presentDataGroups;

	private Passport(Builder b) {
		this.mrz = b.mrz.build();
		this.personalDetails = b.personalDetails;
		this.documentDetails = b.documentDetails;
		this.nationalData = Collections.unmodifiableMap(new LinkedHashMap<>(b.nationalData));
		this.photo = b.photo;
		this.presentDataGroups = Collections.unmodifiableList(b.presentDataGroups);
	}

	/** DG1. Never null; {@link MrzDocument#hasMrz()} is false if DG1 could not be decoded. */
	public MrzDocument mrz() { return mrz; }

	/** DG11, or null. */
	public PersonalDetails personalDetails() { return personalDetails; }

	/** DG12, or null. */
	public DocumentDetails documentDetails() { return documentDetails; }

	/** DG13 issuer-defined entries keyed {@code tag_<hex>}. Never null; unmodifiable. */
	public Map<String, String> nationalData() { return nationalData; }

	/** DG2, or null. */
	public Photo photo() { return photo; }

	/** Data groups EF.COM lists, e.g. {@code "DG1"}. Never null; unmodifiable. */
	public List<String> presentDataGroups() { return presentDataGroups; }

	@Override
	public String toString() {
		// Redacted: identity data must not land in logs by accident.
		return "Passport[" + mrz + (photo == null ? "" : " +photo") + "]";
	}

	/** Accumulator the data-group parsers write into; not public API. */
	static final class Builder {
		final MrzDocument.Builder mrz = new MrzDocument.Builder();
		final Map<String, String> nationalData = new LinkedHashMap<>();
		PersonalDetails personalDetails;
		DocumentDetails documentDetails;
		Photo photo;
		List<String> presentDataGroups = Collections.emptyList();

		Passport build() {
			return new Passport(this);
		}
	}
}
```

`passport/src/main/java/com/adkhambek/reader/passport/DgParser.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parsers for EF.COM, DG1, DG11, DG12 and DG13. DG2 is {@link Dg2}. */
final class DgParser {
	private DgParser() {
	}

	/** DG1: the MRZ, in tag 5F1F. */
	static void parseDg1(byte[] dg1, Passport.Builder out) {
		final Tlv mrz = Tlv.find(Tlv.primitives(dg1), 0x5F1F);
		if (mrz != null) Mrz.decodeInto(new String(mrz.value, StandardCharsets.US_ASCII), out.mrz);
	}

	/** EF.COM: the tag list (5C) of data groups present. */
	static List<String> parseCom(byte[] com) {
		final List<String> out = new ArrayList<>();
		final Tlv list = Tlv.find(Tlv.primitives(com), 0x5C);
		if (list != null) for (final byte b : list.value) out.add(dgName(b & 0xFF));
		return out;
	}

	static void parseDg11(byte[] dg11, Passport.Builder out) {
		final List<Tlv> t = Tlv.primitives(dg11);
		// 5F10 is a fallback for cards that leave the MRZ field blank — never an overwrite.
		out.mrz.personalNumberIfAbsent(text(t, 0x5F10));
		out.personalDetails = new PersonalDetails(text(t, 0x5F0E), text(t, 0x5F0F),
				text(t, 0x5F11), date(t, 0x5F2B), text(t, 0x5F42), text(t, 0x5F12),
				text(t, 0x5F13), text(t, 0x5F14));
	}

	static void parseDg12(byte[] dg12, Passport.Builder out) {
		final List<Tlv> t = Tlv.primitives(dg12);
		out.documentDetails = new DocumentDetails(text(t, 0x5F19), date(t, 0x5F26), text(t, 0x5F1B));
	}

	static void parseDg13(byte[] dg13, Passport.Builder out) {
		for (final Tlv t : Tlv.primitives(dg13)) {
			String text = decodeString(t.value);
			if (looksBinary(text)) text = Bytes.hex(t.value);
			out.nationalData.put(String.format(Locale.ROOT, "tag_%02X", t.tag), text);
		}
	}

	private static String dgName(int tag) {
		switch (tag) {
			case 0x61: return "DG1";
			case 0x75: return "DG2";
			case 0x63: return "DG3";
			case 0x76: return "DG4";
			case 0x65: return "DG5";
			case 0x66: return "DG6";
			case 0x67: return "DG7";
			case 0x68: return "DG8";
			case 0x69: return "DG9";
			case 0x6A: return "DG10";
			case 0x6B: return "DG11";
			case 0x6C: return "DG12";
			case 0x6D: return "DG13";
			case 0x6E: return "DG14";
			case 0x6F: return "DG15";
			case 0x70: return "DG16";
			default: return String.format(Locale.ROOT, "0x%02X", tag);
		}
	}

	private static String text(List<Tlv> t, int tag) {
		final Tlv v = Tlv.find(t, tag);
		return (v == null || v.value.length == 0) ? null : decodeString(v.value);
	}

	private static String date(List<Tlv> t, int tag) {
		final Tlv v = Tlv.find(t, tag);
		if (v == null || v.value.length == 0) return null;
		final byte[] d = v.value;
		if (d.length == 4) {
			// BCD-packed YYYYMMDD.
			return String.format(Locale.ROOT, "%02X%02X-%02X-%02X", d[0], d[1], d[2], d[3]);
		}
		if (d.length == 8) {
			final String s = new String(d, StandardCharsets.US_ASCII);
			return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
		}
		return decodeString(d);
	}

	/** UTF-16BE when most high bytes are ≤ 5 (Cyrillic, CJK in DG11/13); otherwise UTF-8 with '<' filler blanked. */
	private static String decodeString(byte[] data) {
		if (data.length >= 4 && data.length % 2 == 0) {
			int lowHigh = 0;
			for (int i = 0; i < data.length; i += 2) {
				if ((data[i] & 0xFF) <= 5) ++lowHigh;
			}
			if (lowHigh * 100 >= (data.length / 2) * 70) {
				return new String(data, StandardCharsets.UTF_16BE).trim();
			}
		}
		return new String(data, StandardCharsets.UTF_8).trim().replace('<', ' ').trim();
	}

	private static boolean looksBinary(String s) {
		int bad = 0;
		for (int i = 0; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c < ' ' && c != '\n' && c != '\t') ++bad;
			if (c == 0xFFFD) ++bad;
		}
		return bad > s.length() / 4;
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest --tests '*BacTest' --tests '*Dg2Test' --tests '*SecureMessagingTest'`
Expected: PASS — 11 + 4 + 7 = 22. If a `d4_*` test fails, re-check that test's hex against Appendix D.4 before touching `SecureMessaging`: the non-D.4 tests and `BacTest` show whether the crypto itself is sound.

- [ ] **Step 8: Write the file-reader tests (failing)**

`passport/src/test/java/com/adkhambek/reader/passport/EfReaderTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EfReaderTest {

	static final int FID_DG2 = 0x0102;

	/**
	 * Serves EFs from memory over plain (already unwrapped) APDUs and records
	 * every command. Reads past the stored bytes return 9000 with no data —
	 * the EOF quirk EfReader must survive.
	 */
	static final class FakeCard implements Transceiver {
		final List<String> sent = new ArrayList<>();
		private final Map<Integer, byte[]> files = new HashMap<>();
		private byte[] current;

		FakeCard put(int fid, byte[] content) {
			files.put(fid, content);
			return this;
		}

		@Override
		public byte[] transceive(byte[] apdu) {
			sent.add(Bytes.hex(apdu));
			if (apdu[1] == (byte) 0xA4) {
				current = files.get(((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF));
				return current == null ? Replay.hex("6A82") : Replay.hex("9000");
			}
			if (apdu[1] == (byte) 0xB0) {
				final int offset = ((apdu[2] & 0x7F) << 8) | (apdu[3] & 0xFF);
				final int le = (apdu.length == 5)
						? orMax(apdu[4] & 0xFF, 256)
						: orMax(((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF), 65536);
				final int n = Math.max(0, Math.min(le, current.length - offset));
				return Bytes.concat(Arrays.copyOfRange(current, offset, offset + n), Replay.hex("9000"));
			}
			throw new AssertionError("unexpected INS in " + Bytes.hex(apdu));
		}

		private static int orMax(int v, int max) {
			return v == 0 ? max : v;
		}
	}

	/** A DG2-shaped EF: tag 75, long-form length, patterned body. */
	static byte[] ef(int bodyLen) {
		final byte[] body = new byte[bodyLen];
		for (int i = 0; i < bodyLen; ++i) body[i] = (byte) i;
		return SecureMessaging.tlv(0x75, body);
	}

	@Test public void absentFileIsNullAfterOneSelect() throws Exception {
		final FakeCard card = new FakeCard();
		assertNull(new EfReader(card).read(FID_DG2));
		assertEquals(1, card.sent.size());
	}

	/** 1004 bytes: a 5-byte head read, then 223 × 4 + 107. */
	@Test public void readsWholeFileInShortChunks() throws Exception {
		final byte[] file = ef(1000);
		final FakeCard card = new FakeCard().put(FID_DG2, file);

		assertArrayEquals(file, new EfReader(card).read(FID_DG2));
		assertEquals(7, card.sent.size());
		assertEquals("00B0000005", card.sent.get(1));
		assertEquals("00B00005DF", card.sent.get(2));
	}

	/** The header claims 1004 bytes but the card stops serving at 300. */
	@Test public void emptyChunkStopsTheLoop() throws Exception {
		final byte[] truncated = Arrays.copyOf(ef(1000), 300);
		final FakeCard card = new FakeCard().put(FID_DG2, truncated);

		assertArrayEquals(truncated, new EfReader(card).read(FID_DG2));
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest --tests '*EfReaderTest'`
Expected: compilation FAILS — `EfReader` does not exist.

- [ ] **Step 9: Write `EfReader`**

`passport/src/main/java/com/adkhambek/reader/passport/EfReader.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Reads one elementary file: SELECT by FID, then READ BINARY in chunks. */
final class EfReader {
	/** 223 data bytes plus SM overhead fit a 256-byte short response. */
	static final int SHORT_CHUNK = 0xDF;
	/** Tag + up to 0x83 + 3 length bytes: enough to size any file up to 16 MB. */
	static final int HEAD_LEN = 5;

	private final Transceiver sm;

	/** @param sm plain APDU in, unwrapped {@code data ‖ SW} out */
	EfReader(Transceiver sm) {
		this.sm = sm;
	}

	/** @return the whole file, or null if the chip says it does not exist (6A82) */
	byte[] read(int fid) throws IOException {
		final byte[] sel = sm.transceive(
				new byte[]{0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, (byte) (fid >> 8), (byte) fid});
		final int sw = Apdu.sw(sel);
		if (sw == 0x6A82) return null;
		if (sw != Apdu.SW_OK) {
			throw new IllegalStateException(String.format(Locale.ROOT, "SELECT %04X SW=%04X", fid, sw));
		}

		final byte[] head = readBinary(0, HEAD_LEN);
		final int total = totalLength(head);
		final ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.write(head, 0, head.length);
		int off = head.length;
		while (off < total) {
			final byte[] part = readBinary(off, Math.min(SHORT_CHUNK, total - off));
			// A 9000 with no bytes (EOF quirk) would otherwise spin forever.
			if (part.length == 0) break;
			all.write(part, 0, part.length);
			off += part.length;
		}
		return all.toByteArray();
	}

	private byte[] readBinary(int offset, int len) throws IOException {
		// B0 with P1 bit 8 clear addresses the current EF with a 15-bit offset.
		if (offset > 0x7FFF) {
			throw new IllegalStateException("files over 32 KB are not supported (offset " + offset + ")");
		}
		final byte[] r = sm.transceive(
				new byte[]{0x00, (byte) 0xB0, (byte) (offset >> 8), (byte) offset, (byte) len});
		if (!Apdu.ok(r)) {
			throw new IllegalStateException(String.format(Locale.ROOT, "READ BINARY SW=%04X", Apdu.sw(r)));
		}
		return Apdu.data(r);
	}

	/** Size of the whole file from its outer TLV header: tag (1 byte) + length + value. */
	static int totalLength(byte[] head) {
		int i = 1; // data-group tags are all one byte (0x60–0x77)
		if (i >= head.length) return head.length;
		final int b = head[i++] & 0xFF;
		int n;
		if ((b & 0x80) == 0) {
			n = b;
		} else {
			n = 0;
			for (int j = 0; j < (b & 0x7F) && i < head.length; ++j) n = (n << 8) | (head[i++] & 0xFF);
		}
		return i + n;
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest --tests '*EfReaderTest'`
Expected: PASS — 3 tests.

- [ ] **Step 10: Write the reader tests (failing)**

`passport/src/test/java/com/adkhambek/reader/passport/PassportReaderTest.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * SYNTHETIC transcripts. BAC's cryptography is pinned by BacTest and
 * SecureMessagingTest against ICAO vectors; these cover the wiring and the
 * error mapping. A full happy path is not replayable: BAC uses a fresh
 * random RND.IFD and K.IFD on every run.
 */
public class PassportReaderTest {

	private static final String SELECT_EMRTD = "00A4040C07A0000002471001";
	private static final MrzKey KEY = new MrzKey("L898902C<", "690806", "940623");

	private static ReadException.Reason reasonOf(Transceiver card) {
		try {
			PassportReader.readWith(card, KEY);
			fail("expected ReadException");
			return null;
		} catch (ReadException e) {
			return e.reason();
		}
	}

	@Test public void missingAppletIsUnsupported() {
		final Replay card = new Replay().expect(SELECT_EMRTD).reply("6A82");
		assertEquals(ReadException.Reason.UNSUPPORTED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void refusedChallengeIsAuthFailed() {
		final Replay card = new Replay()
				.expect(SELECT_EMRTD).reply("9000")
				.expect("0084000008").reply("6982");
		assertEquals(ReadException.Reason.AUTH_FAILED, reasonOf(card));
		card.assertExhausted();
	}

	@Test public void lostCardIsCardLost() {
		assertEquals(ReadException.Reason.CARD_LOST, reasonOf(apdu -> {
			throw new IOException("Tag was lost.");
		}));
	}

	@Test public void nullKeyIsAProgrammingError() throws ReadException {
		try {
			PassportReader.readWith(new Replay(), null);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}
}
```

Run: `./gradlew :passport:testDebugUnitTest --tests '*PassportReaderTest'`
Expected: compilation FAILS — `PassportReader` does not exist.

- [ ] **Step 11: Write `PassportReader`**

`passport/src/main/java/com/adkhambek/reader/passport/PassportReader.java`:

```java
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
```

- [ ] **Step 12: Run all `passport` tests, check the twins, run the whole build**

Run: `./gradlew :passport:testDebugUnitTest`
Expected: PASS — 25 (twins) + 16 (`MrzTest`) + 4 (`MrzKeyTest`) + 11 (`BacTest`) + 4 (`Dg2Test`) + 7 (`SecureMessagingTest`) + 3 (`EfReaderTest`) + 4 (`PassportReaderTest`) = 74 tests.

Run the twin check. Expected: no output.

```bash
for f in Transceiver Apdu Tlv ReadException Callback Cancellable Call; do
  diff <(tail -n +3 card/src/main/java/com/adkhambek/reader/card/$f.java) \
       <(tail -n +3 passport/src/main/java/com/adkhambek/reader/passport/$f.java) >/dev/null || echo "DRIFT: $f"
done
for f in Replay ReplayTest ApduTest TlvTest CallTest; do
  diff <(tail -n +3 card/src/test/java/com/adkhambek/reader/card/$f.java) \
       <(tail -n +3 passport/src/test/java/com/adkhambek/reader/passport/$f.java) >/dev/null || echo "DRIFT: $f"
done
```

Run: `./gradlew test lint`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add passport settings.gradle.kts
git commit -m "feat(passport): add the passport/ID reader library

Absorbs the MRZ decoder (now whitespace-tolerant for QR payloads),
validates MrzKey input up front, and pins Secure Messaging to the
ICAO 9303-11 Appendix D.4 vectors."
```

---

### Task 4: `passport` — fewer card round trips

Each APDU costs roughly 10–40 ms of NFC latency, which dominates read time. Today a ~20 KB DG2 takes ~90 secured READ BINARYs. This task (a) stops asking for data groups EF.COM says are absent, and (b) reads in large extended-length chunks when **both** the phone and the chip allow it — the chip's limit comes from EF.ATR/INFO, read unprotected before BAC, so a chip that lacks extended length is never sent an extended APDU inside the Secure Messaging session.

**Files:**
- Modify: `passport/src/main/java/com/adkhambek/reader/passport/EfReader.java`
- Modify: `passport/src/main/java/com/adkhambek/reader/passport/PassportReader.java`
- Modify: `passport/src/test/java/com/adkhambek/reader/passport/{EfReaderTest,PassportReaderTest}.java`

**Interfaces:**
- Consumes: Task 3's `EfReader`, `PassportReader.readWith`, `SecureMessaging` (already handles case 2E).
- Produces: `EfReader(Transceiver sm, int maxResponseLength)` (0 = short form only), `static int EfReader.maxResponseFrom(byte[] atrInfo)`; `static Passport PassportReader.readWith(Transceiver, MrzKey, int maxTransceiveLength)`; `static List<Integer> PassportReader.optionalFids(List<String> present)`.

- [ ] **Step 1: Write the failing tests**

In `EfReaderTest`, change the three existing `new EfReader(card)` to `new EfReader(card, 0)`, then add:

```java
	/** Limit 4096 → chunk 4096 − 29 = 4067 (0x0FE3): 10004 bytes is a head read + 3 reads. */
	@Test public void extendedLimitUsesLargeChunks() throws Exception {
		final byte[] file = ef(10_000);
		final FakeCard card = new FakeCard().put(FID_DG2, file);

		assertArrayEquals(file, new EfReader(card, 4096).read(FID_DG2));
		assertEquals(5, card.sent.size());
		assertEquals("00B00005000FE3", card.sent.get(2));
	}

	/** A limit near short-form size buys nothing over 223, so stay short form. */
	@Test public void smallLimitStaysShortForm() throws Exception {
		final FakeCard card = new FakeCard().put(FID_DG2, ef(1000));

		new EfReader(card, 261).read(FID_DG2);
		assertEquals("00B00005DF", card.sent.get(2));
	}

	// --- EF.ATR/INFO: the chip's own limits, tag 7F66 ----------------------

	/** Two INTEGERs: max command (0400), then max response (0800 = 2048). */
	@Test public void maxResponseFromAtrInfo() {
		assertEquals(2048, EfReader.maxResponseFrom(Replay.hex("7F66080202040002020800")));
	}

	@Test public void maxResponseSkipsOtherObjects() {
		assertEquals(2048, EfReader.maxResponseFrom(Replay.hex("4703000000 7F66080202040002020800")));
	}

	@Test public void maxResponseIsZeroWhen7F66IsAbsent() {
		assertEquals(0, EfReader.maxResponseFrom(Replay.hex("4703000000")));
	}

	@Test public void maxResponseIsZeroWhenMalformed() {
		assertEquals(0, EfReader.maxResponseFrom(Replay.hex("7F66")));
	}
```

In `PassportReaderTest`: change `PassportReader.readWith(card, KEY)` in `reasonOf` to `PassportReader.readWith(card, KEY, 0)` and `PassportReader.readWith(new Replay(), null)` to `PassportReader.readWith(new Replay(), null, 0)`. Add imports `java.util.Arrays`, `java.util.Collections`, then add:

```java
	/** With a phone that supports extended length, EF.ATR/INFO is probed first —
	 *  unprotected, before the applet is selected — and its absence is harmless. */
	@Test public void extendedLengthProbeRunsFirstAndToleratesAbsence() {
		final Replay card = new Replay()
				.expect("00A4020C022F01").reply("6A82")
				.expect(SELECT_EMRTD).reply("6A82");
		try {
			PassportReader.readWith(card, KEY, 65279);
			fail("expected ReadException");
		} catch (ReadException e) {
			assertEquals(ReadException.Reason.UNSUPPORTED, e.reason());
		}
		card.assertExhausted();
	}

	@Test public void unreadableComReadsEveryOptionalGroup() {
		assertEquals(Arrays.asList(0x010B, 0x010C, 0x010D, 0x0102), PassportReader.optionalFids(null));
	}

	@Test public void emptyComReadsEveryOptionalGroup() {
		assertEquals(Arrays.asList(0x010B, 0x010C, 0x010D, 0x0102),
				PassportReader.optionalFids(Collections.emptyList()));
	}

	@Test public void readsOnlyGroupsComLists() {
		assertEquals(Collections.singletonList(0x0102),
				PassportReader.optionalFids(Arrays.asList("DG1", "DG2")));
	}
```

Run: `./gradlew :passport:testDebugUnitTest`
Expected: compilation FAILS — the two-argument `EfReader`, `maxResponseFrom`, the three-argument `readWith` and `optionalFids` do not exist.

- [ ] **Step 2: Extend `EfReader`**

In `EfReader.java`:

1. Add `import java.util.List;`.
2. Below `HEAD_LEN`, add:

```java
	/** Response bytes that are not file data: DO87 header 5 + padding ≤ 8 + DO99 4 + DO8E 10 + SW 2. */
	static final int SM_OVERHEAD = 29;
```

3. Replace the field and constructor with:

```java
	private final Transceiver sm;
	private final int chunk;
	private final boolean extended;

	/**
	 * @param sm                plain APDU in, unwrapped {@code data ‖ SW} out
	 * @param maxResponseLength largest response both phone and chip accept; 0 = short form only
	 */
	EfReader(Transceiver sm, int maxResponseLength) {
		this.sm = sm;
		final int extendedChunk = Math.min(maxResponseLength, 65536) - SM_OVERHEAD;
		// Go extended only when it buys more than a short Le can express.
		this.extended = extendedChunk > 0xFF;
		this.chunk = extended ? extendedChunk : SHORT_CHUNK;
	}
```

4. In `read`, the head read stays `readBinary(0, HEAD_LEN, false)`; the loop becomes `readBinary(off, Math.min(chunk, total - off), extended)`.
5. Replace `readBinary` with:

```java
	private byte[] readBinary(int offset, int len, boolean ext) throws IOException {
		// B0 with P1 bit 8 clear addresses the current EF with a 15-bit offset.
		if (offset > 0x7FFF) {
			throw new IllegalStateException("files over 32 KB are not supported (offset " + offset + ")");
		}
		final byte p1 = (byte) (offset >> 8);
		final byte p2 = (byte) offset;
		final byte[] r = sm.transceive(ext
				? new byte[]{0x00, (byte) 0xB0, p1, p2, 0x00, (byte) (len >> 8), (byte) len} // case 2E
				: new byte[]{0x00, (byte) 0xB0, p1, p2, (byte) len});
		if (!Apdu.ok(r)) {
			throw new IllegalStateException(String.format(Locale.ROOT, "READ BINARY SW=%04X", Apdu.sw(r)));
		}
		return Apdu.data(r);
	}
```

6. Add:

```java
	/**
	 * The chip's maximum response length from EF.ATR/INFO: tag 7F66 holds two
	 * INTEGERs, max command length then max response length.
	 *
	 * @return the limit, or 0 if 7F66 is absent or malformed
	 */
	static int maxResponseFrom(byte[] atrInfo) {
		try {
			for (final Tlv t : Tlv.parse(atrInfo, false)) {
				if (t.tag != 0x7F66) continue;
				final List<Tlv> ints = Tlv.findAll(Tlv.parse(t.value, false), 0x02);
				if (ints.size() < 2) return 0;
				int v = 0;
				for (final byte b : ints.get(1).value) v = (v << 8) | (b & 0xFF);
				return v;
			}
		} catch (IllegalArgumentException malformed) {
			// fall through: treat as "no extended length"
		}
		return 0;
	}
```

- [ ] **Step 3: Extend `PassportReader`**

1. Add imports `java.util.ArrayList`, `java.util.List`.
2. Below `SELECT_EMRTD`, add:

```java
	private static final byte[] SELECT_ATR_INFO = {0x00, (byte) 0xA4, 0x02, 0x0C, 0x02, 0x2F, 0x01};
	private static final byte[] READ_UP_TO_256 = {0x00, (byte) 0xB0, 0x00, 0x00, 0x00};
```

3. In `read(IsoDep, MrzKey)`, replace `return readWith(isoDep::transceive, key);` with:

```java
			final int max = isoDep.isExtendedLengthApduSupported() ? isoDep.getMaxTransceiveLength() : 0;
			return readWith(isoDep::transceive, key, max);
```

4. Replace `readWith` with:

```java
	/**
	 * The read itself, over any transceiver. Package-private: the test seam.
	 *
	 * @param maxTransceiveLength the phone's extended-length limit, or 0 if it has none
	 */
	static Passport readWith(Transceiver transceiver, MrzKey key, int maxTransceiveLength)
			throws ReadException {
		if (key == null) throw new IllegalArgumentException("key is null");
		try {
			final Apdu apdu = new Apdu(transceiver);
			// The phone's limit is not enough: an extended APDU sent inside Secure
			// Messaging to a chip that rejects it can end the session. Ask the chip
			// first, unprotected, while a refusal costs nothing.
			final int maxResponse = (maxTransceiveLength > 0)
					? Math.min(maxTransceiveLength, probeMaxResponse(apdu))
					: 0;

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
			final EfReader files = new EfReader(plain -> sm.transceive(apdu, plain), maxResponse);
			final Passport.Builder out = new Passport.Builder();

			List<String> present = null;
			final byte[] com = files.read(FID_COM);
			if (com != null) {
				present = DgParser.parseCom(com);
				out.presentDataGroups = present;
			}
			final byte[] dg1 = files.read(FID_DG1);
			if (dg1 != null) DgParser.parseDg1(dg1, out);

			for (final int fid : optionalFids(present)) {
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

	/**
	 * The optional groups to read, DG2 last. Asking for an absent group costs a
	 * full secured exchange, and some chips answer it in a way that ends the SM
	 * session before DG2. Null or empty means EF.COM was unreadable: read all four.
	 */
	static List<Integer> optionalFids(List<String> present) {
		final int[] fids = {FID_DG11, FID_DG12, FID_DG13, FID_DG2};
		final String[] names = {"DG11", "DG12", "DG13", "DG2"};
		final List<Integer> out = new ArrayList<>(fids.length);
		for (int i = 0; i < fids.length; ++i) {
			if (present == null || present.isEmpty() || present.contains(names[i])) out.add(fids[i]);
		}
		return out;
	}

	/** EF.ATR/INFO (2F01, in the MF) states the chip's APDU limits. 0 if absent or unreadable. */
	private static int probeMaxResponse(Apdu apdu) throws IOException {
		if (!Apdu.ok(apdu.send(SELECT_ATR_INFO))) return 0;
		final byte[] info = apdu.send(READ_UP_TO_256);
		return Apdu.ok(info) ? EfReader.maxResponseFrom(Apdu.data(info)) : 0;
	}
```

- [ ] **Step 4: Run the tests and the build**

Run: `./gradlew :passport:testDebugUnitTest`
Expected: PASS — 74 + 6 (`EfReaderTest`) + 4 (`PassportReaderTest`) = 84 tests.

Run: `./gradlew test lint`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add passport
git commit -m "perf(passport): skip absent data groups, read in extended-length chunks

The chip's limit comes from EF.ATR/INFO (7F66), read unprotected
before BAC, so a chip without extended-length support never gets an
extended APDU inside the Secure Messaging session."
```

---

### Task 5: `qr` — standalone, faster per frame

**Files:**
- Move: `qr/src/main/java/com/adkhambek/reader/qr/view/{QrScanner,QrAnalyzer,QrScannerView,ScannerOverlayView,PreviewGeometry}.java` → `qr/src/main/java/com/adkhambek/reader/qr/`
- Move: `qr/src/test/java/com/adkhambek/reader/qr/view/PreviewGeometryTest.java` → `qr/src/test/java/com/adkhambek/reader/qr/`
- Create: `qr/src/main/java/com/adkhambek/reader/qr/QrCode.java`
- Delete: `qr/src/main/java/com/adkhambek/reader/qr/{id,common}/`, `qr/src/main/java/com/adkhambek/reader/qr/view/QrScanListener.java`
- Modify: `qr/build.gradle.kts`, `qr/gradle.properties`
- Modify: `app/src/main/java/com/adkhambek/reader/sample/QrScannerActivity.java`, `app/build.gradle.kts` (the sample uses `QrIdScannerView`, which this task deletes)

**Interfaces:**
- Consumes: `com.adkhambek.reader.passport.Mrz` and `MrzDocument` (sample app only).
- Produces: `QrScannerView.start(LifecycleOwner, Listener)`; `QrScannerView.Listener { void onScanned(QrCode); default void onError(Throwable) }`; `record QrCode(String text, float[] xs, float[] ys, int imageWidth, int imageHeight, int rotationDegrees)`. `resumeScanning()`, `stop()`, `shutdown()`, `getPreviewView()`, `getScannerOverlay()`, `setSnapAnimDuration(long)`, `setHoldDuration(long)`, `setAutoStopOnScan(boolean)` keep their current signatures.

- [ ] **Step 1: Move files and delete the MRZ and listener layers**

```bash
cd qr/src/main/java/com/adkhambek/reader/qr
git mv view/QrScanner.java view/QrAnalyzer.java view/QrScannerView.java view/ScannerOverlayView.java view/PreviewGeometry.java .
git rm -r -q id common view/QrScanListener.java
cd - >/dev/null
git mv qr/src/test/java/com/adkhambek/reader/qr/view/PreviewGeometryTest.java qr/src/test/java/com/adkhambek/reader/qr/PreviewGeometryTest.java
perl -pi -e 's/^package com\.adkhambek\.reader\.qr\.view;/package com.adkhambek.reader.qr;/' \
   qr/src/main/java/com/adkhambek/reader/qr/*.java qr/src/test/java/com/adkhambek/reader/qr/PreviewGeometryTest.java
```

`qr/build.gradle.kts`: delete the line `    api(project(":common"))` and the blank line after it. Change the comment above `implementation(libs.androidx.core)` to `// Internal only — QrCode carries float[]/int, never a ZXing type.` and the comment above the `api(libs.camera.*)` block to:

```kotlin
    // api, not implementation: these types are on the public surface —
    // QrScannerView.getPreviewView() returns a PreviewView and
    // start() takes a LifecycleOwner.
```

`qr/gradle.properties`: replace `POM_DESCRIPTION` with:

```properties
POM_DESCRIPTION=CameraX + ZXing QR scanner view with an animated lock-on viewfinder. Java API, results on the main thread.
```

- [ ] **Step 2: Write `QrCode`**

`qr/src/main/java/com/adkhambek/reader/qr/QrCode.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

/**
 * One decoded QR code, with enough to draw it over the camera preview: the
 * finder-pattern positions ZXing returned (image space, before rotation), the
 * image size, and the rotation CameraX applies for display. A QR code always
 * has at least three finder patterns, so {@code xs}/{@code ys} are non-empty.
 */
public record QrCode(
		String text,
		float[] xs,
		float[] ys,
		int imageWidth,
		int imageHeight,
		int rotationDegrees
) {
}
```

- [ ] **Step 3: Rewrite `QrAnalyzer`**

Replace `qr/src/main/java/com/adkhambek/reader/qr/QrAnalyzer.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes QR codes from CameraX YUV frames and reports the first one. Further
 * frames are ignored until {@link #reset()}.
 *
 * <p>Tuned for a live stream: no {@code TRY_HARDER} (a missed frame is retried
 * a few milliseconds later anyway), {@link QRCodeReader} directly rather than
 * the multi-format dispatcher, and one luminance buffer reused across frames.
 */
final class QrAnalyzer implements ImageAnalysis.Analyzer {

	interface Callback {
		void onDecoded(QrCode code);
	}

	private static final String TAG = "Read3rQR";

	private final Callback callback;
	private final QRCodeReader reader = new QRCodeReader();
	private final AtomicBoolean done = new AtomicBoolean(false);
	// Touched only on the analysis thread.
	private byte[] luma = new byte[0];

	QrAnalyzer(Callback callback) {
		this.callback = callback;
	}

	void reset() {
		done.set(false);
	}

	@SuppressLint("UnsafeOptInUsageError")
	@Override
	public void analyze(@NonNull ImageProxy image) {
		if (done.get()) {
			image.close();
			return;
		}
		try (image) {
			final Image media = image.getImage();
			if (media == null || media.getFormat() != ImageFormat.YUV_420_888) return;

			final Image.Plane y = media.getPlanes()[0];
			final int width = image.getWidth();
			final int height = image.getHeight();
			if (luma.length != width * height) luma = new byte[width * height];
			packLuminance(y.getBuffer(), y.getRowStride(), width, height, luma);

			final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
					new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)));
			try {
				final Result r = reader.decode(bitmap);
				if (done.compareAndSet(false, true)) {
					callback.onDecoded(toQrCode(r, width, height,
							image.getImageInfo().getRotationDegrees()));
				}
			} catch (ReaderException noCodeInThisFrame) {
				// NotFound, Checksum or Format: nothing usable here; the next frame is coming.
			} finally {
				reader.reset();
			}
		} catch (Throwable t) {
			Log.w(TAG, "analyze failed", t);
		}
	}

	private static QrCode toQrCode(Result r, int w, int h, int rotation) {
		final ResultPoint[] points = r.getResultPoints();
		final int n = (points == null) ? 0 : points.length;
		final float[] xs = new float[n];
		final float[] ys = new float[n];
		for (int i = 0; i < n; ++i) {
			if (points[i] == null) continue;
			xs[i] = points[i].getX();
			ys[i] = points[i].getY();
		}
		return new QrCode(r.getText(), xs, ys, w, h, rotation);
	}

	/** CameraX rows are padded to rowStride; ZXing needs them packed into {@code out}. */
	private static void packLuminance(ByteBuffer src, int rowStride, int width, int height, byte[] out) {
		if (rowStride == width) {
			src.get(out, 0, Math.min(out.length, src.remaining()));
			return;
		}
		for (int row = 0; row < height; ++row) {
			src.position(row * rowStride);
			src.get(out, row * width, Math.min(width, src.remaining()));
		}
	}
}
```

- [ ] **Step 4: Rewrite `QrScanner`**

Replace `qr/src/main/java/com/adkhambek/reader/qr/QrScanner.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX binding behind {@link QrScannerView}: a preview use case plus an
 * analysis use case running {@link QrAnalyzer}. Everything except analysis
 * runs on the main thread, and results are posted there.
 */
final class QrScanner {

	interface Sink {
		void onCode(QrCode code);

		void onError(Throwable t);
	}

	private static final String TAG = "Read3rQR";

	private final Context context;
	private final ExecutorService analysisExec = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "read3r-qr-analyzer");
		t.setDaemon(true);
		return t;
	});
	private final Handler main = new Handler(Looper.getMainLooper());

	private QrAnalyzer analyzer;
	private ProcessCameraProvider provider;
	private Sink sink;
	private boolean shutDown;
	// Bumped by start() and stop(). The provider listener captures its own
	// value and bails if a later call superseded it, so a slow provider future
	// from before onPause cannot bind the camera after stop().
	private int startGen;
	// Fields, not lambda captures, so shutdown() can drop them and a pending
	// provider future cannot pin a destroyed Activity.
	private PreviewView previewView;
	private LifecycleOwner lifecycle;

	QrScanner(Context context) {
		this.context = context.getApplicationContext();
	}

	void start(PreviewView previewView, LifecycleOwner lifecycle, Sink sink) {
		this.previewView = previewView;
		this.lifecycle = lifecycle;
		this.sink = sink;
		final int myGen = ++startGen;

		final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
		future.addListener(() -> {
			if (shutDown || myGen != startGen) return;
			final PreviewView pv = this.previewView;
			final LifecycleOwner lo = this.lifecycle;
			if (pv == null || lo == null) return;
			try {
				provider = future.get();
				bind(pv, lo);
			} catch (Throwable t) {
				Log.e(TAG, "camera init failed", t);
				final Sink s = this.sink;
				if (s != null) s.onError(t);
			}
		}, ContextCompat.getMainExecutor(context));
	}

	private void bind(PreviewView previewView, LifecycleOwner lifecycle) {
		final Preview preview = new Preview.Builder().build();
		preview.setSurfaceProvider(previewView.getSurfaceProvider());

		analyzer = new QrAnalyzer(code -> main.post(() -> {
			final Sink s = sink;
			if (s != null) s.onCode(code);
		}));
		final ImageAnalysis analysis = new ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build();
		analysis.setAnalyzer(analysisExec, analyzer);

		provider.unbindAll();
		provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
	}

	void stop() {
		++startGen;
		if (provider != null) provider.unbindAll();
	}

	/** Re-arm the analyzer so the next frame can decode again. */
	void resume() {
		if (analyzer != null) analyzer.reset();
	}

	void shutdown() {
		shutDown = true;
		previewView = null;
		lifecycle = null;
		sink = null;
		stop();
		analysisExec.shutdown();
	}
}
```

- [ ] **Step 5: Rewrite `QrScannerView`**

Replace `qr/src/main/java/com/adkhambek/reader/qr/QrScannerView.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.qr;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Drop-in QR scanner: a CameraX preview under a {@link ScannerOverlayView}.
 * On a decode it snaps the reticle onto the code, holds, then calls back.
 *
 * <pre>{@code
 * QrScannerView qr = new QrScannerView(this);
 * qr.start(this, code -> show(code.text()));   // ComponentActivity / Fragment
 * }</pre>
 *
 * <p>The host requests {@code CAMERA}. Without it the view sits idle; call
 * {@link #start} again once it is granted.
 */
public final class QrScannerView extends FrameLayout {

	/** Results arrive on the main thread. */
	public interface Listener {
		/** A code was decoded; fires after the lock-on hold. */
		void onScanned(@NonNull QrCode code);

		/** Camera initialisation failed. */
		default void onError(@NonNull Throwable t) {
		}
	}

	private static final String TAG = "Read3rQR";

	/** Default lock-on snap duration in ms. */
	public static final long DEFAULT_SNAP_ANIM_MS = 280;
	/** Default time the lock-on stays visible before the callback, in ms. */
	public static final long DEFAULT_HOLD_MS = 650;

	private final PreviewView preview;
	private final ScannerOverlayView overlay;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LifecycleHandler lifecycleHandler = new LifecycleHandler();
	private final QrScanner.Sink sink = new QrScanner.Sink() {
		@Override
		public void onCode(QrCode code) {
			deliverScanned(code);
		}

		@Override
		public void onError(Throwable t) {
			Log.w(TAG, "camera error", t);
			if (listener != null) listener.onError(t);
		}
	};

	private QrScanner scanner;
	private LifecycleOwner lifecycleOwner;
	private Listener listener;
	private Runnable pendingDeliver;

	private long snapAnimMs = DEFAULT_SNAP_ANIM_MS;
	private long holdMs = DEFAULT_HOLD_MS;
	private boolean autoStopOnScan = true;

	public QrScannerView(@NonNull Context ctx) {
		this(ctx, null);
	}

	public QrScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		this(ctx, attrs, 0);
	}

	public QrScannerView(@NonNull Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(ctx, attrs, defStyleAttr);

		preview = new PreviewView(ctx);
		preview.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(preview);

		overlay = new ScannerOverlayView(ctx);
		overlay.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addView(overlay);
	}

	/**
	 * Scan while {@code owner} is resumed and CAMERA is granted: the camera
	 * stops on pause and is released on destroy. Call again after the user
	 * grants CAMERA — an already-resumed owner starts the camera immediately.
	 */
	public void start(@NonNull LifecycleOwner owner, @NonNull Listener listener) {
		this.listener = listener;
		if (lifecycleOwner != null) lifecycleOwner.getLifecycle().removeObserver(lifecycleHandler);
		lifecycleOwner = owner;
		// addObserver replays the events up to the current state, so ON_RESUME
		// arrives now if the owner is already resumed.
		owner.getLifecycle().addObserver(lifecycleHandler);
	}

	/** Lock-on snap animation duration (default {@value DEFAULT_SNAP_ANIM_MS} ms). */
	public void setSnapAnimDuration(long ms) {
		this.snapAnimMs = Math.max(1, ms);
	}

	/** Time the lock-on is held before {@link Listener#onScanned} (default {@value DEFAULT_HOLD_MS} ms; 0 fires at once). */
	public void setHoldDuration(long ms) {
		this.holdMs = Math.max(0, ms);
	}

	/** When true (default) the camera stops after a scan; false allows continuous scanning via {@link #resumeScanning}. */
	public void setAutoStopOnScan(boolean autoStop) {
		this.autoStopOnScan = autoStop;
	}

	/** Accept the next code. The reticle stays where the last lock-on left it. */
	public void resumeScanning() {
		cancelPendingDeliver();
		overlay.unfreezeScanLine();
		if (scanner != null) {
			scanner.resume();
			if (lifecycleOwner != null && hasCameraPermission()) {
				scanner.start(preview, lifecycleOwner, sink);
			}
		}
	}

	/** Stop the camera without releasing it. A pending lock-on is cancelled. */
	public void stop() {
		cancelPendingDeliver();
		if (scanner != null) scanner.stop();
	}

	/** Release everything. Called automatically on the owner's ON_DESTROY. */
	public void shutdown() {
		cancelPendingDeliver();
		if (scanner != null) {
			scanner.shutdown();
			scanner = null;
		}
		if (lifecycleOwner != null) {
			lifecycleOwner.getLifecycle().removeObserver(lifecycleHandler);
			lifecycleOwner = null;
		}
	}

	@NonNull public PreviewView getPreviewView() { return preview; }

	@NonNull public ScannerOverlayView getScannerOverlay() { return overlay; }

	private boolean hasCameraPermission() {
		return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_GRANTED;
	}

	private void cancelPendingDeliver() {
		if (pendingDeliver != null) {
			main.removeCallbacks(pendingDeliver);
			pendingDeliver = null;
		}
	}

	private void deliverScanned(@NonNull QrCode code) {
		final RectF target = overlay.imageRectToView(
				code.imageWidth(), code.imageHeight(), code.rotationDegrees(), code.xs(), code.ys());
		overlay.freezeScanLine();
		overlay.animateReticleTo(target, snapAnimMs);

		cancelPendingDeliver();
		pendingDeliver = () -> {
			pendingDeliver = null;
			if (autoStopOnScan && scanner != null) scanner.stop();
			if (listener != null) listener.onScanned(code);
		};
		main.postDelayed(pendingDeliver, holdMs);
	}

	/** Kept inner so the lifecycle callbacks are not public API on the view. */
	private final class LifecycleHandler implements DefaultLifecycleObserver {
		@Override
		public void onResume(@NonNull LifecycleOwner owner) {
			if (!hasCameraPermission()) return;
			if (scanner == null) scanner = new QrScanner(getContext());
			scanner.resume();
			scanner.start(preview, owner, sink);
		}

		@Override
		public void onPause(@NonNull LifecycleOwner owner) {
			stop();
		}

		@Override
		public void onDestroy(@NonNull LifecycleOwner owner) {
			shutdown();
		}
	}
}
```

`ScannerOverlayView.java` and `PreviewGeometry.java` need only the package change from Step 1.

- [ ] **Step 6: Move the sample's QR screen to the new API**

In `app/build.gradle.kts` `dependencies`, add `    implementation(project(":passport"))` below `    implementation(project(":qr"))`.

Replace `app/src/main/java/com/adkhambek/reader/sample/QrScannerActivity.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.adkhambek.reader.passport.Mrz;
import com.adkhambek.reader.passport.MrzDocument;
import com.adkhambek.reader.qr.QrCode;
import com.adkhambek.reader.qr.QrScannerView;

public final class QrScannerActivity extends ComponentActivity implements QrScannerView.Listener {

	private QrScannerView qr;
	private TextView output;

	private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(),
			granted -> {
				if (granted) {
					qr.start(this, this);
				} else {
					output.setText(R.string.qr_permission_needed);
					Toast.makeText(this, R.string.qr_permission_needed, Toast.LENGTH_LONG).show();
				}
			});

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		qr = new QrScannerView(this);
		qr.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		root.addView(qr);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setPadding(32, 32, 32, 32);
		output.setText(R.string.qr_prompt);
		final ScrollView outputScroll = new ScrollView(this);
		outputScroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
		outputScroll.addView(output);
		root.addView(outputScroll);

		setContentView(root);

		qr.start(this, this);
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			permissionLauncher.launch(Manifest.permission.CAMERA);
		}
	}

	@Override
	public void onScanned(@NonNull QrCode code) {
		// An ID card's QR may carry its MRZ. Check digits first: a random payload
		// of the right length would otherwise decode into plausible-looking fields.
		final String text = code.text();
		output.setText(Mrz.isValid(text)
				? render(Mrz.decode(text))
				: getString(R.string.qr_raw_prefix, text));
	}

	@Override
	public void onError(@NonNull Throwable t) {
		output.setText(getString(R.string.error_prefix, String.valueOf(t.getMessage())));
	}

	private static String render(MrzDocument d) {
		final StringBuilder sb = new StringBuilder();
		line(sb, "Document type", d.documentType());
		line(sb, "Issuing country", d.issuingCountry());
		line(sb, "Document number", d.documentNumber());
		line(sb, "Last name", d.lastName());
		line(sb, "First name", d.firstName());
		line(sb, "Sex", d.sex());
		line(sb, "Nationality", d.nationality());
		line(sb, "Date of birth", d.dateOfBirth());
		line(sb, "Date of expiry", d.dateOfExpiry());
		line(sb, "Personal number", d.personalNumber());
		line(sb, "Optional data", d.optionalData());
		return sb.toString();
	}

	static void line(StringBuilder sb, String label, String value) {
		if (value == null || value.isEmpty()) return;
		sb.append(label);
		for (int i = label.length(); i < 18; ++i) sb.append(' ');
		sb.append(": ").append(value).append('\n');
	}
}
```

- [ ] **Step 7: Run tests and the build**

Run: `./gradlew :qr:testDebugUnitTest`
Expected: PASS — 14 `PreviewGeometryTest` tests.

Run: `./gradlew test lint :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `qr` no longer depends on `:common`.

Manual check, with a device attached: `./gradlew :app:installDebug`, open "QR code", grant the camera, scan any QR code — the reticle snaps to it and the text appears. Deny the camera on a fresh install — the screen shows the permission message and does not crash.

- [ ] **Step 8: Commit**

```bash
git add qr app
git commit -m "refactor(qr): standalone library with start(owner, listener)

Drops the MRZ variant (apps call passport's Mrz on the text) and the
Result/listener layer. Per frame: no TRY_HARDER, QRCodeReader
directly, one reused luminance buffer."
```

---

### Task 6: Sample app on the new NFC readers

**Files:**
- Create: `app/src/main/java/com/adkhambek/reader/sample/Nfc.java`
- Modify: `app/src/main/java/com/adkhambek/reader/sample/{BankCardActivity,IdReaderActivity}.java`
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`
- Delete: `app/src/main/res/xml/nfc_tech_filter.xml`

**Interfaces:**
- Consumes: `CardReader`, `Card`, `CardApp` (Task 2); `PassportReader`, `Passport`, `MrzKey`, `MrzDocument`, `PersonalDetails`, `DocumentDetails`, `Photo` (Task 3); `Callback`, `Cancellable`, `ReadException` from each.
- Produces: a sample app with no dependency on `common`, `nfc` or `iso7816`.

- [ ] **Step 1: Point the app at the new libraries**

In `app/build.gradle.kts` `dependencies`, replace

```kotlin
    implementation(project(":common"))
    implementation(project(":nfc"))
    implementation(project(":qr"))
    implementation(project(":passport"))
```

with

```kotlin
    implementation(project(":card"))
    implementation(project(":passport"))
    implementation(project(":qr"))
```

and bump `versionCode = 16` to `versionCode = 17`.

- [ ] **Step 2: Write the reader-mode helper**

`app/src/main/java/com/adkhambek/reader/sample/Nfc.java`:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.nfc.NfcAdapter;

/**
 * NFC reader mode for the two NFC screens: while enabled, tags go straight to
 * the callback (on a binder thread) instead of through intents.
 */
final class Nfc {
	private static final int FLAGS = NfcAdapter.FLAG_READER_NFC_A
			| NfcAdapter.FLAG_READER_NFC_B
			| NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

	private Nfc() {
	}

	/** Call from onResume. No-op on a device without NFC. */
	static void enable(Activity activity, NfcAdapter.ReaderCallback callback) {
		final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
		if (adapter != null) adapter.enableReaderMode(activity, callback, FLAGS, null);
	}

	/** Call from onPause. */
	static void disable(Activity activity) {
		final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
		if (adapter != null) adapter.disableReaderMode(activity);
	}
}
```

- [ ] **Step 3: Rewrite `BankCardActivity`**

Replace `app/src/main/java/com/adkhambek/reader/sample/BankCardActivity.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adkhambek.reader.card.Callback;
import com.adkhambek.reader.card.Cancellable;
import com.adkhambek.reader.card.Card;
import com.adkhambek.reader.card.CardApp;
import com.adkhambek.reader.card.CardReader;
import com.adkhambek.reader.card.ReadException;

public final class BankCardActivity extends Activity implements NfcAdapter.ReaderCallback {

	private final CardReader reader = new CardReader();
	private TextView textView;
	private Cancellable pending;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		textView = new TextView(this);
		textView.setTextSize(14);
		textView.setPadding(40, 40, 40, 40);
		textView.setTypeface(Typeface.MONOSPACE);
		textView.setMovementMethod(ScrollingMovementMethod.getInstance());
		textView.setText(R.string.bank_prompt);

		final ScrollView scroll = new ScrollView(this);
		scroll.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scroll.addView(textView);
		setContentView(scroll);
	}

	@Override
	protected void onResume() {
		super.onResume();
		Nfc.enable(this, this);
	}

	@Override
	protected void onPause() {
		Nfc.disable(this);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// On the main thread, so the callback cannot fire after this.
		if (pending != null) pending.cancel();
		super.onDestroy();
	}

	/** Binder thread. Hop to the main thread; the reader does its own threading from there. */
	@Override
	public void onTagDiscovered(Tag tag) {
		runOnUiThread(() -> {
			if (pending != null) pending.cancel();
			textView.setText(R.string.reading);
			pending = reader.read(tag, new Callback<Card>() {
				@Override
				public void onSuccess(Card card) {
					textView.setText(card.isUnknown() ? getString(R.string.unknown_card) : format(card));
				}

				@Override
				public void onError(ReadException e) {
					textView.setText(getString(R.string.error_prefix, e.reason() + ": " + e.getMessage()));
				}
			});
		});
	}

	private static String format(Card card) {
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final CardApp a : card.apps()) {
			if (!first) sb.append('\n').append("--------\n\n");
			first = false;

			if (a.label() != null && !a.label().isEmpty()) {
				sb.append(a.label()).append('\n');
				for (int i = 0; i < a.label().length(); ++i) sb.append('=');
				sb.append('\n');
			}
			line(sb, "PAN", a.pan());
			line(sb, "Serial", a.panSequence());
			line(sb, "Holder", join(" · ", a.cardholder(), a.country()));
			line(sb, "Version", a.appVersion());
			line(sb, "Validity", join(" - ", a.effectiveDate(), a.expiryDate()));
			if (a.currency() != null) line(sb, "Currency", a.currency().name());
		}
		return sb.toString();
	}

	private static void line(StringBuilder sb, String label, String value) {
		if (value != null && !value.isEmpty()) sb.append(label).append(": ").append(value).append('\n');
	}

	private static String join(String sep, String a, String b) {
		final boolean ha = a != null && !a.isEmpty();
		final boolean hb = b != null && !b.isEmpty();
		if (ha && hb) return a + sep + b;
		if (ha) return a;
		return hb ? b : null;
	}
}
```

- [ ] **Step 4: Rewrite `IdReaderActivity`**

Replace `app/src/main/java/com/adkhambek/reader/sample/IdReaderActivity.java` with:

```java
/* SPDX-License-Identifier: Apache-2.0 */
package com.adkhambek.reader.sample;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

import com.adkhambek.reader.passport.Callback;
import com.adkhambek.reader.passport.Cancellable;
import com.adkhambek.reader.passport.DocumentDetails;
import com.adkhambek.reader.passport.MrzDocument;
import com.adkhambek.reader.passport.MrzKey;
import com.adkhambek.reader.passport.Passport;
import com.adkhambek.reader.passport.PassportReader;
import com.adkhambek.reader.passport.PersonalDetails;
import com.adkhambek.reader.passport.Photo;
import com.adkhambek.reader.passport.ReadException;

public final class IdReaderActivity extends Activity implements NfcAdapter.ReaderCallback {

	private final PassportReader reader = new PassportReader();
	private TextView output;
	private ImageView photo;
	private MrzKey key;
	private Cancellable pending;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);

		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(40, 40, 40, 40);

		photo = new ImageView(this);
		final LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(500, 600);
		ip.gravity = Gravity.CENTER_HORIZONTAL;
		photo.setLayoutParams(ip);
		photo.setVisibility(View.GONE);
		root.addView(photo);

		output = new TextView(this);
		output.setTextSize(13);
		output.setTypeface(Typeface.MONOSPACE);
		output.setMovementMethod(ScrollingMovementMethod.getInstance());
		output.setText(R.string.id_prompt);
		root.addView(output);

		final ScrollView scroll = new ScrollView(this);
		scroll.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scroll.addView(root);
		setContentView(scroll);

		String doc = getIntent().getStringExtra(IdInputActivity.K_DOC);
		String dob = getIntent().getStringExtra(IdInputActivity.K_DOB);
		String exp = getIntent().getStringExtra(IdInputActivity.K_EXP);
		if (doc == null || dob == null || exp == null) {
			final SharedPreferences prefs = getSharedPreferences(IdInputActivity.PREFS, 0);
			if (doc == null) doc = prefs.getString(IdInputActivity.K_DOC, null);
			if (dob == null) dob = prefs.getString(IdInputActivity.K_DOB, null);
			if (exp == null) exp = prefs.getString(IdInputActivity.K_EXP, null);
		}
		if (doc == null || dob == null || exp == null) {
			output.setText(R.string.id_missing_key);
			return;
		}
		try {
			key = new MrzKey(doc, dob, exp);
		} catch (IllegalArgumentException e) {
			output.setText(getString(R.string.error_prefix, e.getMessage()));
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (key != null) Nfc.enable(this, this);
	}

	@Override
	protected void onPause() {
		Nfc.disable(this);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// On the main thread, so the callback cannot fire after this.
		if (pending != null) pending.cancel();
		recyclePhoto();
		super.onDestroy();
	}

	/** Binder thread. Hop to the main thread; the reader does its own threading from there. */
	@Override
	public void onTagDiscovered(Tag tag) {
		runOnUiThread(() -> {
			if (pending != null) pending.cancel();
			output.setText(R.string.id_reading);
			pending = reader.read(tag, key, new Callback<Passport>() {
				@Override
				public void onSuccess(Passport passport) {
					render(passport);
				}

				@Override
				public void onError(ReadException e) {
					output.setText(getString(R.string.error_prefix, e.reason() + ": " + e.getMessage()));
				}
			});
		});
	}

	private void render(Passport p) {
		final StringBuilder sb = new StringBuilder();
		final MrzDocument m = p.mrz();
		QrScannerActivity.line(sb, "Document type", m.documentType());
		QrScannerActivity.line(sb, "Issuing country", m.issuingCountry());
		QrScannerActivity.line(sb, "Document number", m.documentNumber());
		QrScannerActivity.line(sb, "Last name", m.lastName());
		QrScannerActivity.line(sb, "First name", m.firstName());
		QrScannerActivity.line(sb, "Sex", m.sex());
		QrScannerActivity.line(sb, "Nationality", m.nationality());
		QrScannerActivity.line(sb, "Date of birth", m.dateOfBirth());
		QrScannerActivity.line(sb, "Date of expiry", m.dateOfExpiry());
		QrScannerActivity.line(sb, "Personal number", m.personalNumber());

		final PersonalDetails d = p.personalDetails();
		if (d != null) {
			sb.append("\n-- DG11 --\n");
			QrScannerActivity.line(sb, "Full name", d.fullName());
			QrScannerActivity.line(sb, "Other names", d.otherNames());
			QrScannerActivity.line(sb, "Place of birth", d.placeOfBirth());
			QrScannerActivity.line(sb, "Full DOB", d.fullDateOfBirth());
			QrScannerActivity.line(sb, "Address", d.address());
			QrScannerActivity.line(sb, "Telephone", d.telephone());
			QrScannerActivity.line(sb, "Profession", d.profession());
			QrScannerActivity.line(sb, "Title", d.title());
		}
		final DocumentDetails dd = p.documentDetails();
		if (dd != null) {
			sb.append("\n-- DG12 --\n");
			QrScannerActivity.line(sb, "Issuing authority", dd.issuingAuthority());
			QrScannerActivity.line(sb, "Date of issue", dd.dateOfIssue());
			QrScannerActivity.line(sb, "Endorsements", dd.endorsements());
		}
		if (!p.nationalData().isEmpty()) {
			sb.append("\n-- DG13 --\n");
			for (final Map.Entry<String, String> e : p.nationalData().entrySet()) {
				QrScannerActivity.line(sb, e.getKey(), e.getValue());
			}
		}
		if (!p.presentDataGroups().isEmpty()) {
			// TextUtils.join, not String.join: the latter needs API 26 and minSdk is 21.
			sb.append("\nPresent: ").append(TextUtils.join(" ", p.presentDataGroups())).append('\n');
		}
		output.setText(sb.toString());

		final Photo ph = p.photo();
		if (ph == null) return;
		final Bitmap bmp = decodePhoto(ph.bytes());
		if (bmp == null) {
			// BitmapFactory cannot decode JPEG 2000, the common eMRTD face codec.
			output.append("\n" + getString(R.string.id_photo_not_displayable, ph.format()) + "\n");
			return;
		}
		recyclePhoto();
		photo.setImageBitmap(bmp);
		photo.setVisibility(View.VISIBLE);
	}

	private void recyclePhoto() {
		final Drawable d = photo.getDrawable();
		photo.setImageDrawable(null);
		if (d instanceof BitmapDrawable) {
			final Bitmap b = ((BitmapDrawable) d).getBitmap();
			if (b != null && !b.isRecycled()) b.recycle();
		}
	}

	/** Downsampled to roughly the 500×600 view: decoding the full image on the main thread janks and risks OOM. */
	private static Bitmap decodePhoto(byte[] bytes) {
		final BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

		final BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = 1;
		while (bounds.outWidth / (opts.inSampleSize * 2) >= 500
				&& bounds.outHeight / (opts.inSampleSize * 2) >= 600) {
			opts.inSampleSize *= 2;
		}
		try {
			return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
		} catch (OutOfMemoryError oom) {
			return null;
		}
	}
}
```

- [ ] **Step 5: Drop intent-based NFC dispatch from the manifest**

In `app/src/main/AndroidManifest.xml`, replace the `BankCardActivity` element with:

```xml
        <activity
            android:name="com.adkhambek.reader.sample.BankCardActivity"
            android:configChanges="orientation|screenSize"
            android:exported="false"
            android:label="@string/menu_bank" />
```

Then:

```bash
git rm app/src/main/res/xml/nfc_tech_filter.xml
```

- [ ] **Step 6: Build and check on a device**

Run: `./gradlew test lint :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E "project :(common|nfc|iso7816)"`
Expected: no output.

Manual check, with a device attached: `./gradlew :app:installDebug`. Bank card: tap a contactless card — PAN and expiry appear. ID: enter a document's MRZ fields, tap the document — MRZ fields and (if JPEG) the photo appear. Enter a wrong date and tap — the screen shows `AUTH_FAILED`. Lift the card mid-read — the screen shows `CARD_LOST`.

- [ ] **Step 7: Commit**

```bash
git add app
git commit -m "refactor(sample): use card/passport readers and NFC reader mode"
```

---

### Task 7: Delete the old modules, cut 3.0.0

**Files:**
- Delete: `common/`, `nfc/`, `iso7816/`
- Delete: `build-logic/src/main/kotlin/read3r.jvm-library.gradle.kts`, `build-logic/src/main/kotlin/read3r.kotlin-library.gradle.kts`
- Modify: `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/read3r.android-library.gradle.kts`
- Rewrite: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the 3.0.0 release tree — exactly three publishable libraries plus the sample.

- [ ] **Step 1: Delete the old modules and unused build logic**

```bash
git rm -r -q common nfc iso7816
git rm -q build-logic/src/main/kotlin/read3r.jvm-library.gradle.kts build-logic/src/main/kotlin/read3r.kotlin-library.gradle.kts
```

`settings.gradle.kts` `include(...)` becomes:

```kotlin
include(
    ":app",
    ":card",
    ":passport",
    ":qr",
)
```

`build-logic/build.gradle.kts`: delete the line `    implementation(libs.kotlin.gradlePlugin)`.

`gradle/libs.versions.toml`: delete the `kotlinPlugin = "2.0.21"` and `coroutines = "1.8.1"` versions and the `coroutines-core`, `coroutines-android` and `kotlin-gradlePlugin` libraries. Keep `kotlin` / `kotlin-bom` (the CameraX stdlib alignment in `qr`) and `androidx-annotation`.

`build-logic/src/main/kotlin/read3r.android-library.gradle.kts`: in the header comment, change `(:nfc, :qr)` to `(:card, :passport, :qr)`.

`gradle.properties`: `VERSION_NAME=2.3.0` → `VERSION_NAME=3.0.0`.

- [ ] **Step 2: Rewrite the README**

Replace `README.md` with:

````markdown
# Read3r

Three independent Android libraries: read contactless **bank cards**, ICAO 9303 **passports and ID cards**, and **QR codes**. Each is plain Java, and none depends on another — add only what you use.

| Artifact | Reads | Depends on |
|---|---|---|
| `com.adkhambek.reader:card:3.0.0` | EMV bank cards over NFC | androidx.annotation |
| `com.adkhambek.reader:passport:3.0.0` | ePassports / eMRTD ID cards over NFC, MRZ text | androidx.annotation |
| `com.adkhambek.reader:qr:3.0.0` | QR codes with the camera | CameraX, ZXing |

`minSdk` 21, Java 17. Apache 2.0.

## Reading an NFC card

Both NFC readers work the same way. Turn on reader mode while your screen is visible:

```java
@Override protected void onResume() {
    super.onResume();
    NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
    if (nfc != null) nfc.enableReaderMode(this, this::onTag,
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
}

@Override protected void onPause() {
    NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
    if (nfc != null) nfc.disableReaderMode(this);
    super.onPause();
}
```

Then hand the tag to a reader. The callback runs on the main thread, exactly once:

```java
private final CardReader reader = new CardReader();
private Cancellable pending;

private void onTag(Tag tag) {
    runOnUiThread(() -> pending = reader.read(tag, new Callback<Card>() {
        @Override public void onSuccess(Card card) { show(card); }
        @Override public void onError(ReadException e) { show(e.reason()); }
    }));
}

@Override protected void onDestroy() {
    if (pending != null) pending.cancel();   // no callback after this
    super.onDestroy();
}
```

A passport needs the three MRZ fields printed on its data page:

```java
MrzKey key = new MrzKey("L898902C3", "690806", "940623");   // doc no., YYMMDD birth, YYMMDD expiry
pending = new PassportReader().read(tag, key, callback);
```

Already on a background thread? Use the blocking form: `Card card = reader.read(tag);`.

Either reader takes your own executors: `new CardReader(workExecutor, callbackExecutor)`.

### Errors

`ReadException.reason()` tells you what to do next:

| Reason | Meaning |
|---|---|
| `CARD_LOST` | The card left the field. Ask the user to hold it still. |
| `AUTH_FAILED` | Passport only: the MRZ key is wrong — usually a typo in a date or the document number. |
| `UNSUPPORTED` | Not a card this reader handles. |
| `FAILED` | Anything else; the message has detail. |

### What you get

- **`Card`**: the tag UID and one `CardApp` per payment application — label, PAN, PAN sequence, cardholder, country, `Currency`, effective and expiry dates, app version.
- **`Passport`**: `mrz()` (DG1), `personalDetails()` (DG11), `documentDetails()` (DG12), `nationalData()` (DG13), `photo()` (DG2, JPEG or JPEG 2000), `presentDataGroups()`. Optional groups that are absent are null.

Passport reads use extended-length APDUs when both phone and chip support them, which cuts a photo read from ~90 card exchanges to a handful.

### MRZ without a chip

```java
if (Mrz.isValid(text)) {            // check digits; whitespace is ignored
    MrzDocument doc = Mrz.decode(text);
}
```

## Scanning QR codes

```java
QrScannerView qr = new QrScannerView(this);          // in a ComponentActivity
qr.start(this, code -> show(code.text()));            // main thread, after the lock-on animation
```

Request `CAMERA` yourself. Without it the view waits; call `start` again once it is granted. The camera stops on pause and is released on destroy. `qr.resumeScanning()` accepts the next code; `setHoldDuration`, `setSnapAnimDuration` and `setAutoStopOnScan` tune the lock-on.

To read an ID card's MRZ from its QR code, add `passport` and call `Mrz.isValid` / `Mrz.decode` on `code.text()`.

## Building

```bash
./gradlew test lint                 # unit tests + lint, all modules
./gradlew :app:installDebug         # sample app
./gradlew publishToMavenLocal       # all three libraries → ~/.m2
```

Tests are plain JUnit — no device, no Robolectric. Card conversations are replayed from scripted transcripts; BAC and Secure Messaging are pinned to the ICAO 9303 Appendix D worked examples. Every transcript is synthetic.

`card` and `passport` each carry a private copy of the same small APDU/TLV helpers (`Apdu`, `Tlv`, `Transceiver`, `Call`, `Callback`, `Cancellable`, `ReadException`), so neither depends on the other. Keep the copies identical apart from the package line.

Publishing to Maven Central: set `RELEASE_SIGNING_ENABLED=true` and `SONATYPE_HOST=CENTRAL_PORTAL` in `gradle.properties`, put `mavenCentralUsername` / `mavenCentralPassword` / `signing.*` in `~/.gradle/gradle.properties`, then run `./gradlew publishAndReleaseToMavenCentral`.

## Security

- **PAN and cardholder name** put you in PCI scope if you transmit or store them. The libraries never log them.
- **MRZ data and the DG2 photo** are high-value identity and biometric data. `Passport.toString()` and `MrzDocument.toString()` are redacted; the accessors are not.
- **`Card.uid`** is the tag's NFC UID. Many cards do not randomise it.
- No library performs network I/O.

## License

Apache 2.0. See [LICENSE](LICENSE).
````

- [ ] **Step 3: Verify the release tree**

Run: `./gradlew clean test lint :app:assembleRelease publishToMavenLocal`
Expected: BUILD SUCCESSFUL.

Run: `ls ~/.m2/repository/com/adkhambek/reader/*/3.0.0/*.pom`
Expected: exactly three POMs — `card`, `passport`, `qr`.

Run: `grep -h "<artifactId>" ~/.m2/repository/com/adkhambek/reader/{card,passport,qr}/3.0.0/*.pom | sort -u`
Expected: each POM's own artifactId plus third-party ones only — no `common`, `nfc`, `iso7816` or `mrz`, and none of the three depending on another.

Run the twin check from Task 3 Step 12.
Expected: no output.

Run: `find card passport qr app -path '*/src/main/*' -name '*.java' | xargs wc -l | tail -1`
Expected: below 4361 (the spec's line budget: today's total across all modules, sample app included). Record the number in the commit body.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: delete common, nfc and iso7816; release 3.0.0

BREAKING CHANGE: coordinates change from {common,nfc,qr} to
{card,passport,qr}. Listener managers and Result are replaced by
blocking reads plus Executor + Callback. Nothing was published outside
mavenLocal, so there are no external consumers to migrate.

Library + sample source: <N> lines (was 4361)."
```

Replace `<N>` with the number from Step 3.

---

## Deferred, deliberately

1. **Reading EFs over 32 KB** (odd-INS `B1` with DO54/DO53, DO85 under Secure Messaging). Rare-path code; today's explicit "files over 32 KB are not supported" error stays.
2. **Reusing cipher objects in Secure Messaging.** A CPU saving invisible next to NFC latency.
3. **SFI-addressed READ BINARY and a full-chunk first read.** Each saves one exchange per file, but a chip that rejects either may answer unprotected inside the SM session and drop it. Revisit with real-card transcripts.
4. **Injectable `SecureRandom` in `Bac`.** Would make the BAC happy path replayable end to end.
5. **Real captured transcripts.** Every fixture here is synthetic.
6. **Zero-copy TLV parsing.** `Tlv` copies each value; a ~20 KB photo is copied a few times per read. Negligible next to NFC time.
7. **Cropping QR decoding to the overlay window.** Needs the inverse of `PreviewGeometry`.
