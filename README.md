# Read3r

Android libraries for reading contactless bank cards, ICAO 9303 ePassports / national IDs, and QR codes — from one Gradle project with a small sample app.

- **Coordinates:** `com.adkhambek.reader:{common,nfc,qr}:2.3.0`
- **`compileSdk` 34, `minSdk` 21**, source/target **Java 17**
- **Toolchain:** AGP 8.7.3, Gradle 8.10.2
- **License:** Apache 2.0 — see [LICENSE](LICENSE)

---

## Modules

| Coordinate | Packaging | Source | Purpose |
|---|---|---|---|
| `com.adkhambek.reader:common:2.3.0` | `jar` | `common/` | `Result`, and the ICAO 9303 MRZ / `IdCard` model and decoder. |
| `com.adkhambek.reader:nfc:2.3.0` | `aar` | `nfc/` | NFC foreground dispatch, EMV contactless bank-card reader, ICAO 9303 eMRTD reader. |
| `com.adkhambek.reader:qr:2.3.0` | `aar` | `qr/` | CameraX + ZXing QR scanner with a lock-on viewfinder. |

`nfc` and `qr` each declare `api(project(":common"))`, so consumers get the shared value types transitively.

**`:common` is a plain jar, not an Android library, and that is deliberate.** Nothing in it touches the Android SDK, so it ships no manifest — which means an app that depends only on `:qr` does not end up with `android.permission.NFC` merged into its manifest by way of the shared module. The APDU layer (`Iso7816`, `Hex`) that used to live there is in `:nfc`, where its only callers are.

---

## Project layout

```
Read3r/
├── app/                                 sample app (menu → 3 reader screens)
│   ├── build.gradle.kts                 applies read3r.android-application
│   ├── proguard-rules.pro               Log stripping only — the file explains why there are no -keep rules
│   └── src/main/
│       ├── AndroidManifest.xml          NFC + CAMERA permissions, 5 activities
│       ├── java/com/adkhambek/reader/sample/
│       │   ├── MainActivity.java        3-button menu
│       │   ├── BankCardActivity.java    drives the EMV reader
│       │   ├── IdInputActivity.java     MRZ key form (doc# / DOB / expiry)
│       │   ├── IdReaderActivity.java    drives the eMRTD reader
│       │   └── QrScannerActivity.java   drives the QR reader
│       └── res/{values,xml,drawable-*}/
├── common/                              :common — Result, MRZ/IdCard (plain jar)
├── nfc/                                 :nfc — dispatch, APDU/BER-TLV, EMV + eMRTD
├── qr/                                  :qr — CameraX + ZXing scanner
├── build-logic/                         convention plugins, applied by id
│   └── src/main/kotlin/
│       ├── read3r.android-application.gradle.kts
│       ├── read3r.android-library.gradle.kts     compileSdk, Java 17, lint, publishing
│       └── read3r.jvm-library.gradle.kts
├── gradle/libs.versions.toml            every version, in one place
├── gradle.properties                    GROUP, VERSION_NAME, shared POM_* metadata
├── settings.gradle.kts                  includeBuild("build-logic"); :app :common :nfc :qr
├── LICENSE                              Apache 2.0
└── README.md                            this file
```

Each module's `build.gradle.kts` is down to a plugin id, a `namespace` and its dependencies; `compileSdk`, `minSdk`, Java level, lint config and the publishing setup live in the convention plugins. Per-module POM metadata (`POM_ARTIFACT_ID`, `POM_NAME`, `POM_DESCRIPTION`) is in each library's own `gradle.properties`.

---

## Quick start

### Sample app

```bash
./gradlew :app:installDebug
adb shell am start -n com.adkhambek.reader.sample/.MainActivity
adb logcat -s NFCard:V NFCid:V Read3rQR:V
```

The menu has three buttons: **Bank card (EMV)**, **ID / Passport (eMRTD)**, **QR code**.

### Consuming from another project

After `./gradlew publishToMavenLocal`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

// app/build.gradle.kts
dependencies {
    implementation("com.adkhambek.reader:nfc:2.3.0")   // pulls :common transitively
    implementation("com.adkhambek.reader:qr:2.3.0")    // pulls :common + CameraX + ZXing
}
```

`:qr` also publishes a `kotlin-bom` platform constraint. CameraX 1.3 pulls `kotlin-stdlib-jdk7/jdk8:1.6.21` while modern androidx artifacts pull `kotlin-stdlib:1.8.x`, and 1.8 folded the jdk7/jdk8 classes into the base stdlib — the two together are a duplicate-class dex failure. You don't have to do anything about it: the constraint that aligns them ships with the artifact.

---

## Callback conventions

Two shapes, one per layer. Which you get depends on which layer you use.

**Low-level readers** — `ReaderListener`, `IdReaderListener`, `QrScanListener` — report an optional in-progress hook plus one terminal result:

```java
void onReading();                              // onScanning() for QR
void onResult(Result<T, Throwable> result);
```

`Result<T, E>` is a small sum type: `isOk()` / `isErr()`, `value()` / `error()`, `valueOr(fallback)`. The error side is `Throwable` everywhere, so whatever the reader thread threw arrives intact instead of being re-wrapped.

**Drop-in views** — `QrScannerView.Listener`, `QrIdScannerView.Listener` — split success and failure, so the common case stays a lambda:

```java
void onScanned(T result);
default void onError(Throwable t) { }
```

---

## Bank cards — `com.adkhambek.reader:nfc`

```java
public final class BankCardActivity extends Activity implements ReaderListener {
    private NfcManager nfc;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(...);
        nfc = new NfcManager(this);
        onNewIntent(getIntent()); // a tag may have launched us
    }

    @Override protected void onResume() { super.onResume(); nfc.onResume(this); }
    @Override protected void onPause()  { super.onPause();  nfc.onPause(this);  }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = NfcManager.tagFrom(intent);   // handles the API 33 getParcelableExtra split
        if (tag != null) ReaderManager.readCard(tag, this);
    }

    @Override public void onReading() {
        textView.setText("Reading…");
    }

    @Override public void onResult(Result<Card, Throwable> result) {
        if (result.isErr()) {
            textView.setText("Error: " + result.error().getMessage());
            return;
        }
        for (CardApp a : result.value().apps()) {
            Log.i("APP", a.label() + " PAN=" + a.pan() + " expiry=" + a.expiryDate());
        }
    }
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />

<activity android:name=".BankCardActivity" android:launchMode="singleTask" android:exported="true">
    <intent-filter><action android:name="android.nfc.action.TECH_DISCOVERED" /></intent-filter>
    <meta-data android:name="android.nfc.action.TECH_DISCOVERED"
               android:resource="@xml/nfc_tech_filter" />
</activity>
```

With `res/xml/nfc_tech_filter.xml`:

```xml
<resources>
    <tech-list><tech>android.nfc.tech.IsoDep</tech></tech-list>
</resources>
```

### What you get back

`Card`:

```java
public record Card(String uid, List<CardApp> apps) {
    public boolean isUnknown() { return apps.isEmpty(); }
}
```

`CardApp`:

```java
public record CardApp(
    byte[] aid,            String label,         String pan,
    String panSequence,    String cardholder,    String country,
    Currency currency,     String effectiveDate, String expiryDate,
    String appVersion
) {}
```

`CardApp` overrides `equals`/`hashCode`/`toString` so the `byte[] aid` compares by content — a record's generated versions compare arrays by identity, which would make two reads of the same card unequal.

`Currency` is an enum of ~35 ISO-4217 codes this reader maps. An unrecognised code gives `null` rather than a placeholder constant: the card said something we have no name for, which is different from the card saying nothing.

### Threading

- `ReaderManager.readCard(tag, listener)` — async. NFC I/O runs on a single-thread daemon executor; **both** `onReading()` and `onResult()` are posted to the main looper. The listener is held via `WeakReference`, so a destroyed Activity can't be pinned by a late callback.
- `ReaderManager.readCard(tag)` — synchronous, `@WorkerThread`, returns `Result<Card, Throwable>`. Use from `WorkManager` / coroutines.

### What's read

| Field | Source TLV | Notes |
|-------|------------|-------|
| `pan` | `0x57` Track-2 (split on `'D'`), fallback `0x5A` (strip `'F'` padding) | Formatted in groups of four |
| `expiryDate` | `0x57` chars after `'D'`, fallback `0x5F24` | `YYYY.MM` |
| `effectiveDate` | `0x5F25` | `YYYY.MM` |
| `cardholder` | `0x5F20` | Trimmed |
| `panSequence` | `0x5F34` | Each BCD nibble ≤ 9 |
| `country` | `0x5F28` | ISO-3166 numeric, 4 hex chars |
| `currency` | `0x9F42`, fallback `0x5F2A` | Mapped to `Currency` |
| `appVersion` | `0x9F08` | Hex bytes |
| `label` | `0x50` → `0x9F12` → AID fallback (`identify(aid)`) | "Visa", "Mastercard", "UnionPay", "UZCARD", … |

---

## ID / Passport — `com.adkhambek.reader:nfc`

Reads ICAO 9303 eMRTDs (passports and most national ID cards). Performs Basic Access Control using a key derived from the MRZ — document number, date of birth, date of expiry, all printed on the card — then reads selected data groups through Secure Messaging.

```java
public final class IdReaderActivity extends Activity implements IdReaderListener {
    private NfcManager nfc;
    private MrzKey key;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(...);
        nfc = new NfcManager(this);
        // dob and expiry are YYMMDD, exactly as printed in the MRZ
        key = new MrzKey(documentNumber, dob, expiry);
    }

    @Override protected void onResume() { super.onResume(); nfc.onResume(this); }
    @Override protected void onPause()  { super.onPause();  nfc.onPause(this);  }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = NfcManager.tagFrom(intent);
        if (tag != null && key != null) IdReaderManager.readCard(tag, key, this);
    }

    @Override public void onResult(Result<IdCard, Throwable> result) {
        if (result.isOk()) {
            IdCard c = result.value();
            Log.i("ID", c.lastName() + " " + c.firstName() + " " + c.dateOfBirth());
        }
    }
}
```

Declare the activity `android:launchMode="singleTop"` — `NfcManager` uses foreground dispatch with `FLAG_ACTIVITY_SINGLE_TOP`.

### What's read

`IdCard` is **immutable**. The data-group parsers fill an `IdCard.Builder`; the reader calls `build()` once, so what you receive never changes underneath you. Every field is an accessor, and absent data is `null` (or an empty collection):

- **DG1 (MRZ)**: `documentType()`, `issuingCountry()`, `documentNumber()`, `lastName()`, `firstName()`, `sex()`, `nationality()`, `dateOfBirth()`, `dateOfExpiry()`, `personalNumber()`, `optionalData()`, `rawMrz()`
- **DG11**: `fullName()`, `otherNames()`, `placeOfBirth()`, `fullDateOfBirth()`, `address()`, `telephone()`, `profession()`, `title()`
- **DG12**: `issuingAuthority()`, `dateOfIssue()`, `endorsements()`
- **DG13**: `nationalData()` → `Map<String, String>`, issuer-defined, unmodifiable
- **DG2**: `photoBytes()` → `byte[]` (not copied — treat as read-only), `photoFormat()` → `PhotoFormat { JPEG, JP2, UNKNOWN }`
- **EF.COM**: `presentDataGroups()` → `List<String>`, unmodifiable
- `hasMrz()` — true when a document number was decoded

Dates come back as `YYYY-MM-DD`, or the raw `YYMMDD` field if it wasn't a valid date. `toString()` is deliberately redacted — an `IdCard` holds high-value identity data, and `toString()` is what ends up in a log.

### MRZ, without a chip

`Mrz` is pure string handling and lives in `:common`, so it works on anything carrying an MRZ — a chip read, a scanned QR code, an OCR result:

```java
if (Mrz.isValidMrz(text)) {          // ICAO 9303 check digits
    IdCard c = Mrz.decode(text);
}
```

All three formats are decoded, by length: **TD1** (90 chars, 3×30 — national ID cards), **TD2** (72, 2×36 — older travel documents), **TD3** (88, 2×44 — passports).

`decode()` does not validate: a same-length string of noise decodes to populated but meaningless fields. For untrusted input, gate on `isValidMrz()` first — it verifies the check digits on the document number, date of birth and date of expiry.

### Limitations

- Read-only. No Active Authentication, no Chip Authentication, no PACE — BAC only.
- BAC-protected cards only. PACE-only cards (newer EU passports) won't open.
- Unsigned reads — the library does not verify Document Signer Certificates or perform Passive Authentication. Use the data for display, not for legal trust.

---

## QR code — `com.adkhambek.reader:qr`

CameraX preview + ZXing decoder + a Telegram-style lock-on viewfinder: dim outside a rounded reticle, a sweeping scan line, and brackets that animate to wrap the detected QR on decode.

### Drop-in view

```java
public final class QrScannerActivity extends ComponentActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        QrScannerView qr = new QrScannerView(this);
        qr.setLifecycleOwner(this);                       // auto start/stop/shutdown
        qr.setListener(result -> Log.i("QR", result.text()));
        setContentView(qr);

        // The caller owns the CAMERA runtime permission.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }
}
```

The lifecycle observer is permission-aware: until CAMERA is granted, `ON_RESUME` is a no-op. Once granted, either call `start(owner)` or just let the next resume pick it up.

Manifest:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />

<activity android:name=".QrScannerActivity" android:screenOrientation="portrait" />
```

### ID documents from a QR code

Some ID cards print their MRZ as a QR code on the back. `QrIdScannerView` wraps `QrScannerView` and runs every scan through the MRZ decoder — check-digit validated, so a random QR doesn't come back looking like an identity document:

```java
QrIdScannerView qr = new QrIdScannerView(this);
qr.setLifecycleOwner(this);
qr.setListener(result -> {
    if (result.isMrz()) render(result.mrz);   // IdCard
    else                showRaw(result.text);
});
```

### Result

```java
public record ScanResult(
    String text,
    float[] xs, float[] ys,         // ZXing finder-pattern positions, image space
    int imageWidth, int imageHeight,
    int rotationDegrees             // CameraX display rotation
) {}
```

### Tunables

```java
qr.setSnapAnimDuration(280);         // ms — lock-on animation
qr.setHoldDuration(650);             // ms — hold after lock-on before the listener fires
qr.setAutoStopOnScan(true);          // false → call qr.resumeScanning() for the next code
qr.getScannerOverlay().setReticleRatio(0.72f);
```

### Composing your own UI

If `QrScannerView` is too opinionated, the pieces underneath are public:

- `QrScanner` — CameraX wiring (start/stop against a `PreviewView`)
- `QrAnalyzer` — `ImageAnalysis.Analyzer` decoding YUV frames with ZXing
- `ScannerOverlayView` — dim + reticle + scan line, plus `imageRectToView()` to map image-space points into view space
- `QrScanListener` — the `Result<ScanResult, Throwable>` callback

---

## Testing

```bash
./gradlew test                     # all modules
./gradlew :common:test             # plain jar module — no Android variants
./gradlew :nfc:testDebugUnitTest
./gradlew :qr:testDebugUnitTest
```

HTML reports land in each module's `build/reports/tests/`. Everything is plain JUnit 4 — no Robolectric, no Android framework on the test classpath (`testOptions.unitTests.isReturnDefaultValues = true`).

What's covered:

- **Iso7816** — BER-TLV short/long-form length, multi-byte tags, truncation rejection, padding skip, `BerTLV.encode` for Secure Messaging, `BerHouse.from`, `Response.getPayload`.
- **EMV** — `extractPan`/`extractExpiry` (Track-2 and fallback), `ymFromBcd` (sign-extension regression), `country`, `currency`, `identify` brand fallback, and GPO Format-1 AFL parsing including the long-form length that used to reject valid cards.
- **BAC** — the ICAO 9303 Appendix D worked example: MRZ check digits, `mrzInfo` concatenation, `K_seed`, `K_ENC`/`K_MAC` derivation with DES parity, ISO 9797-1 Algorithm 3 MAC against the published M.IFD vector, padding round-trip.
- **MRZ** — TD1, TD2 and TD3 decoding, the 1900s/2000s birth-year heuristic, invalid-date fallback, and `isValidMrz` against corrupted check digits, corrupted fields, illegal characters and right-length noise.
- **Preview geometry** — all four display rotations, FILL_CENTER letterboxing in both directions, padding, and every degenerate input (null / empty / mismatched point arrays, un-laid-out view, zero-sized image).

That last one is why `ScannerOverlayView`'s coordinate maths lives in a separate `PreviewGeometry` class: as a `View` method it would have needed a laid-out instance and a real `RectF`, neither of which exists on a JVM test classpath.

---

## Lint

Every module runs `lint { warningsAsErrors = true }`, configured once in the convention plugins. The suppressions are deliberate, and commented where they're declared:

- `GradleDependency` / `AndroidGradlePluginVersion` — the versions in `libs.versions.toml` are a pinned working set; "a newer version exists" is not a build error.
- App only: `LockedOrientationActivity` / `DiscouragedApi` (the QR scanner is intentionally portrait-only — rotating a CameraX preview mid-scan is a UX regression), `UnnecessaryRequiredFeature` (two of three flows fundamentally need NFC, so devices without it shouldn't be offered the install), `OldTargetApi` (34 is pinned pending device validation of Android 15 edge-to-edge), and `DataExtractionRules` (`allowBackup="false"` covers API 21-30, `dataExtractionRules` covers 31+; both are set on purpose).

`Iso9797Mac` carries a local `@SuppressLint("GetInstance")` for `DES/ECB/NoPadding`: that is exactly what ISO 9797-1 Algorithm 3 specifies — the chaining is built by the MAC, not by the cipher mode.

---

## Building, publishing

```bash
./gradlew :app:assembleDebug          # debug APK          → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # R8-minified APK    → app/build/outputs/apk/release/
./gradlew assembleRelease             # library AARs       → */build/outputs/aar/
./gradlew publishToMavenLocal         # publish everything → ~/.m2
./gradlew test lint                   # unit tests + lint
```

Publishing uses `com.vanniktech.maven.publish`, configured in the convention plugins. Shared POM metadata (group, version, URL, license, SCM, developer) lives in the root `gradle.properties`; per-module `POM_ARTIFACT_ID` / `POM_NAME` / `POM_DESCRIPTION` live in each library's own `gradle.properties`. All three modules need them — Maven Central rejects a POM without a name and description.

To publish to Maven Central: set `RELEASE_SIGNING_ENABLED=true` in the root `gradle.properties`, add `SONATYPE_HOST=CENTRAL_PORTAL`, put `mavenCentralUsername` / `mavenCentralPassword` / `signing.*` in `~/.gradle/gradle.properties`, then run `./gradlew publishAndReleaseToMavenCentral`.

---

## Security and PCI considerations

The bank-card and ID-card libraries move cleartext personal data into your app's process memory. Once you have it, the rest is your problem.

- **PAN / cardholder name** — placing these in your app puts you in PCI scope if you transmit or persist them. The library does not log either.
- **MRZ data** — high-value identity data. Don't write the MRZ string or any DG fields to logs or storage you don't control. `IdCard.toString()` is redacted on purpose; the accessors are not.
- **DG2 facial image** — biometric data. Treat it with the same care as the MRZ.
- **UID tracking** — `Card.uid` is the NFC tag's ISO 14443-3 UID. Some cards randomize it; many don't.
- **Wipe on background** — if you display PAN or MRZ fields on screen, clear them in `onPause`. The library retains no references after the terminal callback returns.

No network I/O is performed by any library module.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
