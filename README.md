# Read3r

Android libraries for reading contactless bank cards, ICAO 9303 ePassports / national IDs, and QR codes — from one Gradle project with a small sample app.

- **Coordinates:** `com.adkhambek.reader:{common,nfc,qr}:2.3.0`
- **`compileSdk` 34, `minSdk` 21**, source/target **Java 17**
- **Toolchain:** AGP 8.7.3, Gradle 8.10.2
- **License:** Apache 2.0 — see [LICENSE](LICENSE)

---

## Modules

| Coordinate | Source | Purpose |
|---|---|---|
| `com.adkhambek.reader:common:2.3.0` | `common/` | Shared primitives — `Result`, `Hex`, `Iso7816`, and MRZ parsing types. |
| `com.adkhambek.reader:nfc:2.3.0` | `nfc/` | NFC foreground dispatch plus EMV contactless bank-card and ICAO 9303 eMRTD readers. |
| `com.adkhambek.reader:qr:2.3.0` | `qr/` | CameraX + ZXing QR-code scanner with a Telegram-style lock-on viewfinder. |

`nfc` and `qr` each declare `api(project(":common"))` so consumers get the shared value types transitively.

---

## Project layout

```
Read3r/
├── app/                                 sample app (menu → 3 reader screens)
│   ├── build.gradle.kts                 com.android.application; minify + R8 on release
│   ├── proguard-rules.pro               -keep rules for the public API surfaces
│   └── src/main/
│       ├── AndroidManifest.xml          NFC + CAMERA permissions, 5 activities
│       ├── java/com/adkhambek/reader/card/
│       │   ├── MainActivity.java        3-button menu
│       │   ├── BankCardActivity.java    drives the EMV reader
│       │   ├── IdInputActivity.java     MRZ key form (doc# / DOB / expiry)
│       │   ├── IdReaderActivity.java    drives the eMRTD reader
│       │   └── QrScannerActivity.java   drives the QR reader
│       └── res/{values,xml,drawable-*}/
├── common/                              :common (shared primitives)
├── nfc/                                 :nfc (NFC dispatch + EMV/eMRTD readers)
├── qr/                                  :qr (CameraX + ZXing scanner)
├── build.gradle.kts                     root: plugin versions
├── settings.gradle.kts                  includes :app, :common, :nfc, :qr
├── gradle.properties                    GROUP, VERSION_NAME, shared POM_* metadata
├── LICENSE                              Apache 2.0
└── README.md                            this file
```

---

## Quick start

### Sample app

```bash
./gradlew :app:installDebug
adb shell am start -n com.adkhambek.reader.card/.MainActivity
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

---

## Bank cards — `com.adkhambek.reader:nfc`

```java
public final class BankCardActivity extends Activity implements ReaderListener {
    private NfcManager nfc;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(...);
        nfc = new NfcManager(this);
        onNewIntent(getIntent()); // tag may have launched us
    }

    @Override protected void onResume() { super.onResume(); nfc.onResume(this); }
    @Override protected void onPause()  { super.onPause();  nfc.onPause(this);  }

    @Override protected void onNewIntent(Intent intent) {
        Tag tag = NfcManager.tagFrom(intent);
        if (tag != null) ReaderManager.readCard(tag, this);
    }

    @Override public void onReadEvent(SPEC.EVENT event, Object... obj) {
        if (event == SPEC.EVENT.FINISHED) {
            @SuppressWarnings("unchecked")
            Result<Card, Exception> r = (Result<Card, Exception>) obj[0];
            if (r.isOk()) for (CardApp a : r.value().apps()) {
                Log.i("APP", a.label() + " PAN=" + a.pan() + " expiry=" + a.expiryDate());
            }
        }
    }
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />

<activity android:name=".BankCardActivity" android:launchMode="singleTask" android:exported="false">
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
    byte[] aid,            String label,        String pan,
    String panSequence,    String cardholder,   String country,
    SPEC.CUR currency,     String effectiveDate, String expiryDate,
    String appVersion
) {}
```

### Threading

- `ReaderManager.readCard(tag, listener)` — async. Fires `READING` immediately (calling thread), runs NFC I/O on a single-thread daemon executor, posts `FINISHED` to the main looper. Listener held via `WeakReference`.
- `ReaderManager.readCard(tag)` — synchronous, `@WorkerThread`. Use from `WorkManager`/coroutines.

### What's read

| Field | Source TLV | Notes |
|-------|------------|-------|
| `pan` | `0x57` Track-2 (split on `'D'`), fallback `0x5A` (strip `'F'` padding) | Formatted in groups of four |
| `expiryDate` | `0x57` chars after `'D'`, fallback `0x5F24` | `YYYY.MM` |
| `effectiveDate` | `0x5F25` | `YYYY.MM` |
| `cardholder` | `0x5F20` | Trimmed |
| `panSequence` | `0x5F34` | Each BCD nibble ≤ 9 |
| `country` | `0x5F28` | ISO-3166 numeric, 4 hex chars |
| `currency` | `0x9F42`, fallback `0x5F2A` | Mapped to `SPEC.CUR` (~35 currencies) |
| `appVersion` | `0x9F08` | Hex bytes |
| `label` | `0x50` → `0x9F12` → AID fallback (`identify(aid)`) | "Visa", "Mastercard", "UnionPay", "UZCARD", … |

---

## ID / Passport — `com.adkhambek.reader:nfc`

Reads ICAO 9303 eMRTDs (passports + most national ID cards). Performs Basic Access Control (BAC) using a key derived from the MRZ (document number, date of birth, date of expiry — what's printed on the bottom of the card), then reads selected data groups through Secure Messaging.

```java
public final class IdReaderActivity extends ComponentActivity implements IdReaderListener {
    private MrzKey key;
    private NfcAdapter nfc;
    private PendingIntent pi;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ... read documentNumber/dob/expiry from your form / SharedPreferences ...
        key = new MrzKey(documentNumber, dob, expiry);  // dob and expiry are YYMMDD
        nfc = NfcAdapter.getDefaultAdapter(this);
        pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override protected void onResume() {
        super.onResume();
        nfc.enableForegroundDispatch(this, pi,
                new IntentFilter[]{new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)},
                new String[][]{{IsoDep.class.getName()}});
    }

    @Override protected void onNewIntent(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        if (tag != null && key != null) IdReaderManager.readCard(tag, key, this);
    }

    @Override public void onReadEvent(SPEC.EVENT event, Object... obj) {
        if (event == SPEC.EVENT.FINISHED) {
            @SuppressWarnings("unchecked")
            Result<IdCard, Exception> r = (Result<IdCard, Exception>) obj[0];
            if (r.isOk()) {
                IdCard c = r.value();
                Log.i("ID", c.lastName + " " + c.firstName + " " + c.dateOfBirth);
            }
        }
    }
}
```

### What's read

`IdCard` is a mutable bean (filled in incrementally by the data-group parsers):

- **From DG1 (MRZ)**: `documentType`, `issuingCountry`, `documentNumber`, `lastName`, `firstName`, `sex`, `nationality`, `dateOfBirth`, `dateOfExpiry`, `personalNumber`, `optionalData`, `rawMrz`
- **From DG11 (additional personal details)**: `fullName`, `otherNames`, `placeOfBirth`, `fullDateOfBirth`, `address`, `telephone`, `profession`, `title`
- **From DG12 (additional document details)**: `issuingAuthority`, `dateOfIssue`, `endorsements`
- **From DG13 (national data)**: `nationalData: Map<String, String>` (issuer-defined)
- **From DG2 (face)**: `photoBytes: byte[]`, `photoFormat: Dg2.Format { JPEG, JP2, UNKNOWN }`
- **From EF.COM**: `presentDataGroups: List<String>`

### MRZ formats supported

`Mrz.decodeMrz(String, IdCard)` handles all three ICAO 9303 formats based on string length:

- **TD1** (90 chars, 3×30) — national ID cards
- **TD2** (72 chars, 2×36) — older travel documents
- **TD3** (88 chars, 2×44) — passports

### Limitations

- Read-only. No Active Authentication, no Chip Authentication (PACE not yet implemented — BAC only).
- BAC-protected cards only. PACE-only cards (newer EU passports) won't open.
- Unsigned reads — the library does not verify Document Signer Certificates (DSCs) or perform Passive Authentication. Use the data for display, not for legal trust.

---

## QR code — `com.adkhambek.reader:qr`

CameraX preview + ZXing decoder + Telegram-style lock-on viewfinder (dim outside a rounded reticle, scan line, brackets animate to wrap the detected QR on decode).

### Drop-in view (simplest)

```java
public final class QrScannerActivity extends ComponentActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        QrScannerView qr = new QrScannerView(this);
        qr.setLifecycleOwner(this);                         // auto start/stop/shutdown
        qr.setListener(result -> Log.i("QR", result.text()));
        setContentView(qr);

        // Caller is still responsible for CAMERA runtime permission.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }
    // ... ActivityResultLauncher for CAMERA ...
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />

<activity android:name=".QrScannerActivity" android:screenOrientation="portrait" />
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
qr.setHoldDuration(650);             // ms — hold after lock-on before listener fires
qr.setAutoStopOnScan(true);          // false → call qr.resumeScanning() for next code
qr.getScannerOverlay().setReticleRatio(0.72f);
```

### Composing your own UI

If `QrScannerView` is too opinionated, use the lower-level pieces:

- `QrScanner` — CameraX wiring (start/stop with a `PreviewView`)
- `QrAnalyzer` — `ImageAnalysis.Analyzer` decoding YUV frames with ZXing
- `ScannerOverlayView` — dim + reticle + scan line + `imageRectToView()` mapper
- `QrScanListener` — typed `Result<ScanResult, Throwable>` callback

---

## Testing

```bash
./gradlew test
./gradlew :common:testDebugUnitTest
./gradlew :nfc:testDebugUnitTest
./gradlew :qr:testDebugUnitTest
```

HTML reports land in each module's `build/reports/tests/testDebugUnitTest/index.html`. Tests are pure JUnit 4 — no Robolectric, no Android framework on the test classpath (`testOptions.unitTests.isReturnDefaultValues = true`).

Coverage highlights:

- **Iso7816** — BER-TLV short/long-form length, multi-byte tag, truncation rejection, padding-skip, `BerTLV.encode` for SM, `BerHouse.from` shortcut, `Response.getPayload`.
- **EMV** — `extractPan`/`extractExpiry` (Track-2 + fallback), `ymFromBcd` (sign-extension regression), `country`, `currency`, `identify` (brand fallback).
- **BAC** — ICAO 9303 Appendix D worked example: MRZ check digits, `mrzInfo` concatenation, `K_seed`, `K_ENC`/`K_MAC` derivation with DES parity, ISO 9797-1 Algorithm 3 MAC (verified against the published M.IFD vector), padding round-trip.
- **MRZ** — TD1 and TD3 decoders, 1900s vs 2000s date heuristic.

---

## Lint

Every module enables `lint { warningsAsErrors = true }` so new warnings fail the build. The few suppressions in place are deliberate and documented in the build files:

- `Iso9797Mac` uses `DES/ECB/NoPadding` — that's exactly what ISO 9797 Algorithm 3 specifies; chaining is built by the MAC, not the cipher mode.
- App's `screenOrientation="portrait"` on the QR scanner — rotating a CameraX preview mid-scan is a UX regression.
- App's `uses-feature android:hardware.nfc android:required="true"` — two of three flows fundamentally need NFC.
- `allowBackup="false"` + `dataExtractionRules` are set together to cover API 21-30 and 31+ respectively.

---

## Building, publishing

```bash
./gradlew :app:assembleDebug          # debug APK         → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # R8-minified APK   → app/build/outputs/apk/release/
./gradlew assembleRelease             # all library AARs  → */build/outputs/aar/
./gradlew publishToMavenLocal         # publish everything to ~/.m2
./gradlew test                        # JVM unit tests
```

Publishing uses the `com.vanniktech.maven.publish` plugin. Per-module POM metadata lives in each library's own `gradle.properties` (`POM_ARTIFACT_ID`, `POM_NAME`, `POM_DESCRIPTION`); shared metadata (group, version, URL, license, SCM, developer) is in the root `gradle.properties`.

To publish to Maven Central, set `RELEASE_SIGNING_ENABLED=true` in root `gradle.properties`, add `SONATYPE_HOST=CENTRAL_PORTAL`, put `mavenCentralUsername` / `mavenCentralPassword` / `signing.*` in `~/.gradle/gradle.properties`, then run `./gradlew publishAndReleaseToMavenCentral`.

---

## Security and PCI considerations

The bank-card and ID-card libraries move cleartext personal data into your app's process memory. Once you have it, the rest is your problem.

- **PAN / cardholder name** — placing these in your app puts you in PCI scope if you transmit or persist them. The library does not log either.
- **MRZ data** — high-value identity data. Don't write the MRZ string or any DG fields to logs or persistent storage you don't control.
- **DG2 facial image** — biometric data. Treat it with the same care as the MRZ.
- **UID tracking** — `Card.uid` is the NFC tag's ISO 14443-3 UID. Some cards randomize it; many don't.
- **Wipe on background** — if you display PAN or MRZ fields on screen, clear them in `onPause`. The library does not retain references after the `FINISHED` callback returns.

No network I/O is performed by any library module.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
