# Read3r 3.0 — module and API redesign

**Date:** 2026-07-25
**Status:** approved, ready for implementation planning
**Supersedes:** the `:app` / `:common` / `:nfc` / `:qr` layout on branch `read3r-restructure`

---

## Why

The 2.3.0 structure has three problems, all raised by the project owner:

1. **Modules are split by transport, not by what is being read.** `:nfc` contains two unrelated readers — EMV bank cards and ICAO 9303 eMRTDs — that share only the APDU layer. A passport app ships the EMV currency tables; a bank-card app ships the BAC/3DES crypto and DG parsers.
2. **The async API is a hand-rolled observable.** `ReaderListener` / `IdReaderListener` / `QrScanListener` are Java callback interfaces backed by a static single-thread executor, a static main-looper `Handler`, and a `WeakReference` to guard against leaks. Nothing is injectable, cancellable, or testable without a device.
3. **`:common` is named for its role in the build, not its contents.** Even trimmed to `Result` + MRZ types, that name attracts whatever doesn't fit elsewhere.

A fourth problem surfaced while designing the fix, and it justifies the work on its own: **the readers cannot be tested.** `EMV.readCard(IsoDep)` takes a concrete Android class, so the 19 EMV tests only cover leaf parsing helpers. No test exercises a card conversation.

Nothing is published outside the author's local `~/.m2`. **There are zero external consumers**, so this can be a clean break with no deprecation cycle.

---

## Decisions

Each was decided explicitly during design; the rationale is recorded so it doesn't have to be re-litigated.

| # | Decision | Rationale |
|---|---|---|
| D1 | Java core libraries with separate Kotlin `-ktx` modules | Author's call. Keeps a usable Java surface; puts all coroutine machinery in an opt-in layer. |
| D2 | The Java core is **blocking only** — no progress callbacks | Author's call. Smallest possible core. Adding progress later is an overload, which is source- and binary-compatible. |
| D3 | ktx exposes **`Flow`**, not `suspend` | Author's call. Composes with `retry`/`catch`/`stateIn`; cold, and cancels with its scope. |
| D4 | `Flow<T>` emitting the value, errors via Flow's own error channel | Avoids a shared `ReadState` type, which would need a ninth artifact with a generic name — the exact thing being deleted. Also keeps `CancellationException` handling correct for free. |
| D5 | EMV and eMRTD are **separately consumable artifacts** | Author's call. Shared layers become artifacts named for their contents. |
| D6 | `:qr` Java core is **decode only**; ktx owns CameraX | Author's call. Makes the decoder unit-testable from a `byte[]`, and matches the "core is pure, ktx is framework" split of the NFC modules. |
| D7 | QR views live in `:qr-ktx`, converted to Kotlin | Keeps the count at 8 artifacts. Splitting a `:qr-view` artifact out later is additive, not breaking. |
| D8 | Sample app rewritten in Kotlin against the ktx APIs | The sample should demonstrate the recommended path. Java core is covered by unit tests. |
| D9 | Readers are **instances**, not static managers | Injectable into a `ViewModel`, replaceable by a fake in tests. |
| D10 | Errors are **thrown, typed exceptions**; `Result<T, E>` is deleted | Kotlin has no checked exceptions, so ktx callers are unaffected; Java callers get compiler-enforced handling. |

### Explicitly rejected

- **`Flow<ReadState<T>>` with sealed Loading/Success/Failure** — requires a shared type across all three ktx modules, i.e. a ninth artifact with a generic name. Also invites catching `Throwable` into `Failure`, which swallows `CancellationException`.
- **`Flow<kotlin.Result<T>>`** — competes with Flow's error channel and reliably swallows `CancellationException` via `runCatching`.
- **Keeping the listeners and wrapping them in `callbackFlow`** — leaves the disliked API permanently supported, doubles the documented surface, and leaves the static executor in control of dispatch so ktx cannot honour cancellation.
- **One artifact plus one ktx** — puts CameraX and ZXing on the compile classpath of every consumer.

---

## Module graph

```
:mrz  (jar, zero deps)          :iso7816 (aar)
   │           │                     │        │
   │           └──────┐        ┌─────┘        │
   │                  │        │              │
   │              ┌───▼────────▼───┐    ┌─────▼─────┐
   │              │    :emrtd      │    │   :emv    │
   │              └───────┬────────┘    └─────┬─────┘
   │                      │                   │
   │              ┌───────▼────────┐    ┌─────▼─────┐
   │              │  :emrtd-ktx    │    │ :emv-ktx  │
   │              └────────────────┘    └───────────┘
   │
   └──────┐   :qr (aar, zxing)
          │        │
       ┌──▼────────▼──┐
       │   :qr-ktx    │  CameraX + Flow + views
       └──────────────┘
```

| Artifact | Packaging | Language | Depends on | Contents |
|---|---|---|---|---|
| `com.adkhambek.reader:mrz` | **jar** | Java | — | `Mrz`, `MrzDocument`, `PhotoFormat`, `MrzFormatException` |
| `com.adkhambek.reader:iso7816` | aar | Java | — | `ApduChannel`, `IsoDepChannel`, BER-TLV types, APDU framing, `Hex`, `NfcManager` |
| `com.adkhambek.reader:emv` | aar | Java | `iso7816` | `EmvReader`, `EmvCard`, `EmvApp`, `Currency` |
| `com.adkhambek.reader:emv-ktx` | aar | Kotlin | `emv` | `Flow` extensions |
| `com.adkhambek.reader:emrtd` | aar | Java | `iso7816`, `mrz` | `PassportReader`, `Passport`, BAC, Secure Messaging, DG parsers, `MrzKey` |
| `com.adkhambek.reader:emrtd-ktx` | aar | Kotlin | `emrtd` | `Flow` extensions |
| `com.adkhambek.reader:qr` | aar | Java | zxing | `QrDecoder`, `QrCode`, `PreviewGeometry` |
| `com.adkhambek.reader:qr-ktx` | aar | Kotlin | `qr`, `mrz`, CameraX | `Flow` extensions, `QrScannerView`, `QrIdScannerView`, `ScannerOverlayView` |

`:mrz` stays a plain jar with no manifest, so a QR-only consumer inherits no NFC permission — carrying forward the fix from the previous round.

MRZ-from-QR is a ktx-layer feature: only `:qr-ktx` depends on `:mrz`. A Java consumer can add `:mrz` and call `Mrz.decode(qrCode.text())` directly.

---

## API surface

### `:mrz`

```java
public final class MrzDocument {          // immutable; built via MrzDocument.Builder
    String documentType();   String issuingCountry(); String documentNumber();
    String lastName();       String firstName();      String sex();
    String nationality();    String dateOfBirth();    String dateOfExpiry();
    String personalNumber(); String optionalData();   String raw();
}

public final class Mrz {
    public static boolean isValid(String mrz);                         // ICAO 9303 check digits
    public static MrzDocument decode(String mrz) throws MrzFormatException;
}

public enum PhotoFormat { JPEG, JP2, UNKNOWN }
```

Splitting `MrzDocument` out of the old 27-field `IdCard` removes a real wart: a QR scan currently returns an `IdCard` with 15 permanently-null fields for data groups that only exist on a chip.

Dates remain `YYYY-MM-DD`, falling back to the raw `YYMMDD` when the field is not a valid date. The 1900s/2000s birth-year heuristic is unchanged.

### `:iso7816`

```java
public interface ApduChannel extends Closeable {
    byte[] transceive(byte[] apdu) throws IOException;
}

public final class IsoDepChannel implements ApduChannel {   // ~15 lines over android.nfc.tech.IsoDep
    public IsoDepChannel(IsoDep isoDep);
}
```

Plus the existing BER-TLV machinery (`BerT`, `BerL`, `BerV`, `BerTLV`, `BerHouse`, `Response`) and `Hex`, moved unchanged. The APDU chaining logic in `StdTag` (61xx / 6Cxx handling, 16-exchange cap) becomes a decorator over `ApduChannel`.

`ApduChannel` is the single most important change in this design: it is what makes every reader testable.

### `:emv`

```java
public final class EmvReader {
    @WorkerThread public EmvCard read(Tag tag)        throws ReadException;   // convenience
    @WorkerThread public EmvCard read(ApduChannel ch) throws ReadException;   // testable
}

public record EmvCard(String uid, List<EmvApp> apps) {
    public boolean isUnknown();
}

public record EmvApp(
    byte[] aid,        String label,         String pan,
    String panSequence, String cardholder,   String country,
    Currency currency,  String effectiveDate, String expiryDate,
    String appVersion
) { }   // equals/hashCode/toString overridden so aid compares by content

public enum Currency { USD, EUR, /* ~35 codes */ }
```

The `read(Tag)` overload wraps the tag in an `IsoDepChannel`. Unrecognised currency codes yield `null`, not a placeholder constant.

### `:emrtd`

```java
public final class PassportReader {
    @WorkerThread public Passport read(Tag tag, MrzKey key)        throws ReadException;
    @WorkerThread public Passport read(ApduChannel ch, MrzKey key) throws ReadException;
}

public final class Passport {
    MrzDocument mrz();                    // DG1
    PersonalDetails personalDetails();    // DG11, nullable
    DocumentDetails documentDetails();    // DG12, nullable
    Map<String, String> nationalData();   // DG13, unmodifiable, never null
    Photo photo();                        // DG2, nullable
    List<String> presentDataGroups();     // EF.COM, unmodifiable, never null
}

public record Photo(byte[] bytes, PhotoFormat format) { }   // bytes not copied; document as read-only

public final class MrzKey {              // unchanged: documentNumber + YYMMDD dob + YYMMDD expiry
    public MrzKey(String documentNumber, String dateOfBirth, String dateOfExpiry);
}
```

`PersonalDetails` (DG11: full name, other names, place of birth, full DOB, address, telephone, profession, title) and `DocumentDetails` (DG12: issuing authority, date of issue, endorsements) replace flattening eleven more fields onto one class.

### Error model

```java
public class ReadException extends Exception { }
public final class CardLostException            extends ReadException { }  // tag left the field mid-read
public final class AuthenticationException      extends ReadException { }  // BAC failed
public final class UnsupportedDocumentException extends ReadException { }  // no eMRTD applet / no PPSE
```

Checked, because these are recoverable conditions a Java caller should be forced to consider. Kotlin has no checked exceptions, so ktx callers are unaffected.

`AuthenticationException` is the one that matters for UX: BAC failing almost always means the user mistyped the document number or a date. Today that surfaces as `RuntimeException("BAC MAC mismatch")`.

### `:qr`

```java
public final class QrDecoder {
    @Nullable public QrCode decode(byte[] luminance, int width, int height);
}

public record QrCode(String text, float[] xs, float[] ys, int imageWidth, int imageHeight) { }
```

No rotation field — rotation is a CameraX concept and belongs to the ktx layer.

### ktx layer

```kotlin
// :emv-ktx
public fun EmvReader.cards(tag: Tag): Flow<EmvCard> =
    flow { emit(read(tag)) }.flowOn(Dispatchers.IO)

// :emrtd-ktx
public fun PassportReader.passports(tag: Tag, key: MrzKey): Flow<Passport> =
    flow { emit(read(tag, key)) }.flowOn(Dispatchers.IO)

// :qr-ktx
public data class QrScan(val code: QrCode, val rotationDegrees: Int)

public fun QrScanner.codes(preview: PreviewView, owner: LifecycleOwner): Flow<QrScan> =
    callbackFlow {
        // CameraX bind; analyzer → QrDecoder → trySend
        awaitClose { provider.unbindAll() }
    }
```

**Naming constraint, not preference:** a Kotlin extension can never shadow a member with the same name and parameter list — the member always wins, silently. An extension `EmvReader.read(Tag): Flow<EmvCard>` would never be called because `read(Tag): EmvCard` already exists. Hence the plural-noun names.

Call site:

```kotlin
reader.passports(tag, key)
    .catch { e -> _ui.value = when (e) {
        is AuthenticationException -> UiState.WrongMrzKey
        is CardLostException       -> UiState.KeepCardStill
        else                       -> UiState.Error(e)
    } }
    .collect { _ui.value = UiState.Loaded(it) }
```

### Threading

The core has **no threading policy**. It blocks on the calling thread and is annotated `@WorkerThread`. The ktx layer chooses the dispatcher via `flowOn(Dispatchers.IO)`; a caller who wants a different one overrides it. Cancellation is scope cancellation — leaving the coroutine scope ends the read.

Java consumers supply their own executor, which a well-behaved library should require rather than deciding for them.

---

## What is deleted

| Deleted | Replacement |
|---|---|
| `ReaderListener`, `IdReaderListener`, `QrScanListener` | `Flow` extensions |
| `ReaderManager` / `IdReaderManager` static `EXEC` and static `MAIN` `Handler` | `flowOn(Dispatchers.IO)` |
| `WeakReference<Listener>` leak guard | Flow cancellation |
| `Result<T, E>` | Thrown typed exceptions + Flow's error channel |
| Hand-rolled `WorkerThread` annotation | `androidx.annotation.WorkerThread` (the reason to avoid it — pulling Kotlin stdlib — no longer applies) |
| `IdCard` (27 fields) | `MrzDocument` + `Passport` composition |
| `:common` | `:mrz` and `:iso7816` |
| `QrScanner`'s `startGen` generation counter and `clearListener` | `awaitClose` in `callbackFlow` |

`NfcManager` (foreground dispatch) survives. It is inherently coupled to `Activity.onNewIntent`, which a `Flow` cannot intercept without the Activity forwarding the intent, so there is no coroutine idiom that replaces it.

It lands in `:iso7816`, which is a deliberate exception to the naming rule this design is built on: foreground dispatch is not APDU framing. The alternatives are worse — duplicating it into `:emv` and `:emrtd`, or minting a ninth artifact for ~45 lines. `:iso7816` is therefore documented as "the shared Android-NFC layer", not "BER-TLV only". If it ever accumulates a second unrelated tenant, split it then.

---

## Testing

### Transcript replay — the payoff

`ApduChannel` makes a fake trivial:

```java
ApduChannel fake = new ReplayChannel()
    .expect("00A404000E325041592E5359532E4444463031 00").reply("6F1A840E32...9000")
    .expect("00A4040007A0000000031010 00").reply("6F2A8407A0...9000")
    .expect("80A80000238321...").reply("771282021C...9000");

EmvCard card = new EmvReader().read(fake);
assertEquals("4111 1111 1111 1111", card.apps().get(0).pan());
```

This covers PPSE select → AID iteration → GPO → AFL parsing → record reads → field extraction as one path. None of that is reachable by any current test.

The same technique gives BAC a genuine end-to-end test against the ICAO 9303 Appendix D transcript, instead of testing `kSeed` and the retail MAC in isolation and assuming the wiring between them is right.

`ReplayChannel` should also assert that no unexpected APDU is sent, so a reader that starts issuing an extra command fails loudly.

### Existing coverage

All 83 current tests carry over, redistributed: `:mrz` 14, `:iso7816` 21, `:emv` 19, `:emrtd` 15, `:qr` 14. New transcript tests are additive.

`:qr`'s `PreviewGeometry` tests move with the decoder; the geometry itself is consumed by the views in `:qr-ktx` but stays testable as pure Java in `:qr`.

---

## Build changes

- New convention plugin `read3r.kotlin-library` for the three ktx modules: Kotlin Android plugin, `explicitApi()`, `jvmTarget = 17`.
- `read3r.jvm-library`, `read3r.android-library`, `read3r.android-application` carry over unchanged from the previous round.
- `gradle/libs.versions.toml` gains `kotlin` and `kotlinx-coroutines`.
- Root `gradle.properties`: `VERSION_NAME=3.0.0`.
- Eight `gradle.properties` files, one per module, for `POM_ARTIFACT_ID` / `POM_NAME` / `POM_DESCRIPTION`.
- The `kotlin-bom` constraint currently in `:qr` is reconsidered: with Kotlin a first-class dependency of the ktx modules, alignment comes from the Kotlin plugin's own stdlib version. The constraint likely still belongs on `:qr-ktx` for CameraX's sake; verify against a real resolution before removing it.

---

## Versioning

`3.0.0`. Coordinates change from `{common, nfc, qr}` to `{mrz, iso7816, emv, emv-ktx, emrtd, emrtd-ktx, qr, qr-ktx}`.

No deprecation shims, no compatibility layer, no migration guide beyond a README section — there are no external consumers to migrate. All eight artifacts release at the same version from a single `VERSION_NAME`.

A BOM artifact is **not** part of this design. Revisit if the artifacts ever version independently.

---

## Out of scope

- PACE, Active Authentication, Chip Authentication, Passive Authentication. `:emrtd` remains BAC-only and unsigned; the README's existing limitations section carries over verbatim.
- Compose wrappers for the QR scanner. The `Flow` is Compose-friendly as-is; a `@Composable` wrapper is a later, additive artifact.
- Reinstating the China transit readers (PBOC, FeliCa, city union) dropped in the 2.x rewrite.
- Progress reporting on reads (D2). Addable later as an overload without breaking anything.

---

## Risks

1. **Eight artifacts must stay version-aligned.** Mitigated by a single `VERSION_NAME` and always releasing together.
2. **Converting ~660 lines of View code to Kotlin** (D7) is the largest chunk of mechanical risk. `ScannerOverlayView`'s coordinate maths is already extracted into `PreviewGeometry` and covered by 14 tests, so the part most likely to break silently is protected. The rest is `Canvas` drawing, verifiable by eye.
3. **The transcript fixtures need real captures.** Synthetic APDU transcripts risk encoding the current implementation's assumptions rather than real card behaviour. Prefer transcripts captured from actual cards where possible, and mark synthetic ones as such.
4. **Scope.** This touches every file in the repository. It should be sequenced so the build stays green at each step — likely: extract `:mrz` and `:iso7816` first, then `:emv`, then `:emrtd`, then `:qr`, then the ktx modules, then the sample.
