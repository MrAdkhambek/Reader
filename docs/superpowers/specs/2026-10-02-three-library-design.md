# Read3r 3.0 — three libraries

**Status:** approved design, 2026-10-02. Supersedes the 2026-07-25 reader API redesign and its plan (removed; see git history).

## Goal

Ship Read3r as **three independently consumable Android libraries**, with as little source code as possible:

| Artifact | For |
|---|---|
| `com.adkhambek.reader:qr` | Scanning QR codes with the camera |
| `com.adkhambek.reader:passport` | Reading ICAO 9303 passports and eMRTD ID cards over NFC |
| `com.adkhambek.reader:card` | Reading EMV bank cards over NFC |

An app adds only the libraries it uses, and pulls nothing from the others.

**Success criteria**

- Exactly three published artifacts. None depends on another.
- Plain Java API: blocking calls plus an `Executor` + callback form. No Kotlin and no coroutines in the libraries.
- Less library source than today's 4,361 lines (measured over `src/main` across all modules, sample app included).
- `./gradlew test lint` is green after every implementation task.
- Every existing unit test survives in the library that owns its code.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Three artifacts, **no shared artifact**. Code both NFC libraries need is copied into each. | Author's call (option B). A shared `nfc-core` would avoid duplication, but would make four published artifacts. |
| D2 | Each copy is a **compact helper** of about 150 lines, not the 354-line `Iso7816` class hierarchy | Keeps the cost of D1 small. Each library copies only what it calls. |
| D3 | Copies are **package-private classes in each library's main package** (`…reader.card`, `…reader.passport`) | The different packages stop `passport` and `card` from clashing in one app, and package-private keeps the helpers out of the public API altogether. |
| D4 | **Java only.** Blocking `read(...)` plus `read(..., Callback)` that runs on an `Executor` | Author's call. The previous plan's four `-ktx` modules are dropped. Kotlin apps can call the Java API directly. |
| D5 | **One callback per call**, returning `Cancellable`. No observer registration. | Author's call. Nothing to unregister, so nothing can leak. |
| D6 | One `ReadException` per library, with a `Reason` enum | Replaces a base class plus three subclasses, which would be eight files across two copies. |
| D7 | `qr` has no MRZ code. `QrIdScannerView` and `QrIdResult` are deleted. | Keeps `qr` standalone. An app scanning MRZ-bearing QR codes calls `Mrz.decode(text)` from `passport`. |
| D8 | `NfcManager` is deleted | Author's call. Apps call `NfcAdapter.enableReaderMode` directly (shown in the README and the sample). It is about five lines, and reader mode is the better API than foreground dispatch. |
| D9 | `ScannerOverlayView` is kept | Author's call. It is the visible part of the QR library. |
| D10 | The sample app stays in Java and is updated to the new API | The previous plan's Kotlin rewrite is dropped. |
| D11 | Readers are instances; executors can be injected | Testable with a direct executor, and replaceable by a fake in app tests. |

> **Amended 2026-10-02:** D3 is superseded by `2026-10-02-inner-packages-design.md` P1–P2. Helpers now live in function sub-packages, public with `@RestrictTo(LIBRARY)`.

## Artifacts

All three are Android libraries (aar) built with the existing `read3r.android-library` convention plugin, and published through vanniktech with their own `gradle.properties` (`POM_ARTIFACT_ID`, `POM_NAME`, `POM_DESCRIPTION`).

| Artifact | Public package | Dependencies | Public types |
|---|---|---|---|
| `qr` | `com.adkhambek.reader.qr` | CameraX (`api`), ZXing (`implementation`), androidx.core, lifecycle | `QrScannerView`, `ScannerOverlayView`, `QrCode` |
| `passport` | `com.adkhambek.reader.passport` | androidx.annotation | `PassportReader`, `Passport`, `PersonalDetails`, `DocumentDetails`, `Photo`, `PhotoFormat`, `MrzKey`, `Mrz`, `MrzDocument`, `MrzFormatException`, `ReadException`, `Callback`, `Cancellable` |
| `card` | `com.adkhambek.reader.card` | androidx.annotation | `CardReader`, `Card`, `CardApp`, `Currency`, `ReadException`, `Callback`, `Cancellable` |

`passport` and `card` each declare `<uses-permission android:name="android.permission.NFC"/>` in their manifest. `qr` declares `CAMERA`. The runtime permission request is the app's job.

### What each NFC library copies

| Helper (package-private) | `card` | `passport` |
|---|---|---|
| `Apdu`: transceive with 61xx GET RESPONSE chaining, 6Cxx Le retry, and a 16-exchange cap | yes | yes |
| `Tlv`: parse BER-TLV, flatten primitives, find first/all by tag | yes | yes |
| `Bytes`: hex encode | yes | yes |
| `Bytes`: concat, slice, xor, ISO 7816-4 pad/unpad, hex decode | — | yes |
| `Transceiver`: `byte[] transceive(byte[])`, the test seam | yes | yes |
| `Call`, `Callback`, `Cancellable`, `ReadException` | yes | yes |

`Apdu`, `Tlv`, `Transceiver`, `Call`, `Callback`, `Cancellable` and `ReadException` are identical in both libraries apart from the package line. BER-TLV *encoding* is needed only by Secure Messaging, so it lives there rather than in `Tlv`, which keeps the twins identical.

## API

### NFC readers

`passport` is shown. `card` is identical except that it takes no key and returns `Card`.

```java
public final class PassportReader {
	/** Work on a shared daemon thread; callbacks on the main thread. */
	public PassportReader();
	public PassportReader(Executor work, Executor callbacks);

	/** Blocking. Call from a background thread. */
	@WorkerThread
	public Passport read(Tag tag, MrzKey key) throws ReadException;

	/** Runs the blocking read on `work`; reports exactly once on `callbacks`, unless cancelled. */
	public Cancellable read(Tag tag, MrzKey key, Callback<Passport> callback);
}

public interface Callback<T> {
	void onSuccess(T value);
	void onError(ReadException e);
}

public interface Cancellable {
	void cancel();
}
```

**Executor defaults.** `work` is one daemon thread per library, created lazily and shared by every reader instance. One NFC tag is read at a time, so more threads would not help. `callbacks` posts to `Looper.getMainLooper()`.

**Callback guarantees.**
- Fires exactly once (`onSuccess` or `onError`), unless cancelled first.
- The callback is held strongly until it fires or is cancelled. There is no `WeakReference`, so results are never dropped silently.
- Argument errors (null tag, null key) are thrown synchronously from `read(...)`, as `IllegalArgumentException`. They are not delivered to the callback.

**`cancel()`.**
- Called on the callback executor's thread (the main thread by default), the callback will not fire after it returns. Called from another thread, a delivery already running may still complete.
- It closes the `IsoDep` connection, so a read in progress fails fast instead of running on for seconds.
- It is idempotent, and does nothing after the callback has fired.

**Internal seam.** `read(Tag, ...)` wraps `IsoDep.get(tag)` and delegates to a package-private `read(Transceiver, ...)`. Tests call that overload with a replayed transcript. It is not public API.

### Errors

One class per library, identical in shape:

```java
public final class ReadException extends Exception {
	public enum Reason {
		CARD_LOST,     // IOException: the tag left the field mid-read
		AUTH_FAILED,   // passport only: BAC failed — almost always a mistyped document number or date
		UNSUPPORTED,   // not ISO-DEP, no eMRTD applet, or no PPSE
		FAILED         // anything else: bad status word, malformed data
	}
	public Reason reason();
}
```

It is checked, so Java callers of the blocking `read` must handle it. The `card` copy keeps `AUTH_FAILED` in its enum (never produced) so the two read the same.

BAC failures inside `passport` are signalled internally by a `BacException`, which the reader maps to `AUTH_FAILED`. This matches on type, not on message strings.

### Result types

`card`. These carry over from today's `Card`/`CardApp`, repackaged:

```java
public record Card(String uid, List<CardApp> apps) { public boolean isUnknown(); }
// A malformed EMV record is skipped; the card is still read from its other records.
public record CardApp(byte[] aid, String label, String pan, String panSequence,
		String cardholder, String country, Currency currency,
		String effectiveDate, String expiryDate, String appVersion) { }
		// equals/hashCode/toString compare aid by content, as today
public enum Currency { USD, EUR, /* … today's ~35 codes */ }
```

`passport`. The 27-field `IdCard` is split by source:

```java
public final class Passport {
	MrzDocument mrz();                    // DG1
	PersonalDetails personalDetails();    // DG11, nullable
	DocumentDetails documentDetails();    // DG12, nullable
	Map<String, String> nationalData();   // DG13, unmodifiable, never null
	Photo photo();                        // DG2, nullable
	List<String> presentDataGroups();     // EF.COM, unmodifiable, never null
}
public record PersonalDetails(String fullName, String otherNames, String placeOfBirth,
		String fullDateOfBirth, String address, String telephone, String profession, String title) { }
public record DocumentDetails(String issuingAuthority, String dateOfIssue, String endorsements) { }
public record Photo(byte[] bytes, PhotoFormat format) { }
public enum PhotoFormat { JPEG, JP2, UNKNOWN }
public final class MrzKey { public MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry); }

public final class MrzDocument { /* documentType, issuingCountry, documentNumber, lastName, firstName,
                                    sex, nationality, dateOfBirth, dateOfExpiry, personalNumber,
                                    optionalData, raw */ }
public final class Mrz {
	public static boolean isValid(String mrz);                         // ICAO 9303 check digits
	public static MrzDocument decode(String mrz) throws MrzFormatException;
}
public final class MrzFormatException extends IllegalArgumentException { }
```

`Mrz` ignores whitespace, so an MRZ scanned from a QR code with line breaks between its lines validates and decodes as is (this replaces the stripping `QrIdScannerView` did). `MrzFormatException` is unchecked: callers gate on `isValid` first.

`MrzKey` validates at construction: the document number may contain only A–Z, 0–9 and `<` (lower case is upper-cased), and both dates must be six digits. Bad input throws `IllegalArgumentException` before any card I/O instead of failing mid-read.

`Mrz`, `MrzDocument` and `MrzFormatException` come from the uncommitted `mrz/` work in progress, moved into `passport`, so its 14-test `MrzTest` carries over as-is. `MrzDocument`'s builder is package-private.

### QR

`QrScannerView` keeps today's API. It is renamed where needed; the logic is unchanged:

```java
public final class QrScannerView extends FrameLayout {
	public interface Listener {
		void onScanned(QrCode code);              // main thread, after the lock-on hold
		default void onError(Throwable t) { }     // CameraX init failures
	}
	public void start(LifecycleOwner owner, Listener listener);
	public void resumeScanning();
	public void stop();
	public void shutdown();
	public ScannerOverlayView getScannerOverlay();
	public void setSnapAnimDuration(long ms);
	public void setHoldDuration(long ms);
	public void setAutoStopOnScan(boolean autoStop);
}
public record QrCode(String text, float[] xs, float[] ys,
		int imageWidth, int imageHeight, int rotationDegrees) { }   // was ScanResult
```

`start(owner, listener)` replaces the separate `setLifecycleOwner`, `setListener` and `start` calls. `QrScanner`, `QrAnalyzer`, `QrScanListener` and `PreviewGeometry` become package-private. CameraX runs frame analysis on its own executor; results are posted to the main thread.

## Performance changes included

Carried over from the optimisation review, filtered for the minimal-code goal:

| Change | Where | Note |
|---|---|---|
| `org.gradle.caching`, `parallel`, `configuration-cache` | root `gradle.properties` | If a plugin is incompatible with the configuration cache, drop only that flag and name the plugin in the commit. |
| Read optional data groups only when EF.COM lists them | `passport` | Null or empty EF.COM → read all, as today. DG2 stays last. |
| Extended-length READ BINARY | `passport` | Used only when `IsoDep.isExtendedLengthApduSupported()` **and** the chip's EF.ATR/INFO (`2F01`, tag `7F66`) allows it. EF.ATR/INFO is read unprotected before BAC, so a chip without it costs one harmless 6A82. Chunk = chip max response − 29 bytes SM overhead, used only if that exceeds 255. Secure Messaging `wrap` learns ISO 7816-4 cases 2E/3E/4E. |
| Single-pass CBC-MAC | `passport` (`Iso9797Mac`) | One `DES/CBC` pass with a zero IV in place of a per-block loop. Shorter than the current code. |
| `TRY_HARDER` off; `QRCodeReader` used directly | `qr` (`QrAnalyzer`) | Fewer lines, and less work per frame. |
| Reuse one luminance buffer per stream | `qr` (`QrAnalyzer`) | Removes the per-frame allocation and the row buffer. |

**Not included** (on the deferred list, with reasons):
- Reading EFs over 32 KB with odd-INS `B1`/DO85: about 50 lines of rare-path code. The clear out-of-range error stays.
- Caching cipher objects in Secure Messaging: a CPU saving too small to notice next to NFC latency.
- SFI-addressed reads, a larger first read: both risk an unprotected error that ends the SM session.
- Zero-copy TLV parsing.
- Cropping QR decoding to the overlay.

## Testing

Plain JUnit, no device and no Robolectric.

| Library | Tests |
|---|---|
| `card` | `EmvTest` (19, moved). `Apdu`/`Tlv` tests split from `Iso7816Test`. Transcript tests through a replay `Transceiver`: no PPSE → `UNSUPPORTED`, `IOException` → `CARD_LOST`. Callback tests with a direct executor: success, error, cancel suppresses the callback. |
| `passport` | `BacTest` (11), `Dg2Test` (4), `MrzTest` (14), all moved. `Apdu`/`Tlv`/`Bytes` tests from `Iso7816Test`. Secure Messaging against ICAO 9303-11 Appendix D.4 (wrap/unwrap SELECT and READ BINARY; **verify the hex against the published document before relying on it**). Transcript tests: no applet → `UNSUPPORTED`, GET CHALLENGE refused → `AUTH_FAILED`, extended-length probe ordering. Data-group selection from EF.COM. Extended-length chunk sizing and case 2E framing. Callback tests as for `card`. |
| `qr` | `PreviewGeometryTest` (14, moved). |

Each NFC library has its own small replay `Transceiver` test helper (the successor to `ReplayChannel`). Its tests stay with that helper, as test code in each library.

## Migration

The build stays green throughout: new libraries are built alongside the old modules, and the old ones are deleted last.

1. **Gradle flags.** Independent of everything else.
2. **`card`:** a new module from `nfc/…/card`, plus its compact helpers and tests.
3. **`passport`:** a new module from `nfc/…/id` and the `mrz/` work in progress, plus the passport optimisations.
4. **`qr`:** rewritten in place. Package `…qr.view` becomes `…qr`, `qr/id` and the `:common` dependency are removed, and the QR optimisations go in.
5. **Sample app:** moved to the new API and to `enableReaderMode`.
6. **Cleanup:** delete `common`, `nfc`, `iso7816` and `mrz`. Set `VERSION_NAME=3.0.0` and update the README (installation, the three APIs, `enableReaderMode` snippet).

`:iso7816` (already committed on this branch) is not published. Its code is the source for the compact helpers, and it is deleted in step 6.

## Versioning

`3.0.0`. Coordinates change from `{common, nfc, qr}` to `{qr, passport, card}`, and the listener API and `Result` are gone. Nothing was published outside `mavenLocal`, so there are no external consumers to migrate.

## Risks

- **Duplicated helpers drift.** A fix in `card/internal/Apdu` may need applying to `passport/internal/Apdu`. Mitigation: the two copies stay small; a comment at the top of each names its twin; and the ported `Iso7816Test` cases run against both.
- **Extended-length reads on unusual chips.** These are gated on the chip's own EF.ATR/INFO declaration, never on the phone alone. Every transcript in the test suite is synthetic, so real-card validation is still needed before release.
- **`cancel()` closing `IsoDep`** affects other code sharing the same `Tag` connection. This is documented on `cancel()`.
