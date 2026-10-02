# Read3r 3.0 — inner packages by function

Date: 2026-10-02
Amends: `2026-10-02-three-library-design.md` decision D3.

## Goal

Each library's root package currently holds every class, public API and helpers alike. Split each library into sub-packages by function so a reader can see the layers (ISO 7816 transport, BAC/Secure Messaging, data groups, MRZ, camera, view) from the directory tree. Behaviour does not change.

## Decision

| # | Decision | Why |
|---|---|---|
| P1 | Root package keeps the public API plus `Call`. Helpers move to function sub-packages. | Users import from the root, as today; the tree shows the layers. |
| P2 | A helper used across a package boundary becomes `public` and is annotated `@RestrictTo(RestrictTo.Scope.LIBRARY)`. | Java has no cross-package "internal". `@RestrictTo` is the AndroidX convention: lint's `RestrictedApi` check reports any use from a consuming app as an error. It is not a compile-time guarantee, and that is accepted. |
| P3 | `androidx.annotation` becomes `api` in `card` and `passport`, and is added as `api` to `qr`. | Lint has to see the annotation on the consumer's classpath to enforce P2. |
| P4 | Twin files move into an identical `iso7816` sub-package in both `card` and `passport`. | They stay byte-identical apart from line 2, so the twin check still holds. |

D3 in the three-library spec ("package-private classes in each library's main package") is replaced by P1–P2. The clash protection still holds, because the sub-packages remain under `…reader.card` and `…reader.passport`.

## Layout

```
com.adkhambek.reader.card
├── CardReader, Card, CardApp, Currency, Callback, Cancellable, ReadException   public (unchanged)
├── Call                                                                          package-private
├── iso7816   Apdu, Tlv, Transceiver, Bytes                                       @RestrictTo
└── emv       Emv                                                                 @RestrictTo

com.adkhambek.reader.passport
├── PassportReader, Passport, PersonalDetails, DocumentDetails, Photo,
│   PhotoFormat, Callback, Cancellable, ReadException                             public (unchanged)
├── Call                                                                          package-private
├── iso7816   Apdu, Tlv, Transceiver, Bytes                                       @RestrictTo
├── mrz       Mrz, MrzDocument, MrzFormatException, MrzKey                        public (package moves)
├── bac       Bac, Iso9797Mac, SecureMessaging                                    @RestrictTo
└── dg        EfReader, DgParser, Dg2                                             @RestrictTo

com.adkhambek.reader.qr
├── QrScannerView, QrCode                                                         public (unchanged)
├── camera    QrScanner, QrAnalyzer                                               @RestrictTo
└── view      ScannerOverlayView                                                  public (package moves)
              PreviewGeometry                                                     @RestrictTo
```

## Members that open up

Only members actually referenced across a package boundary become `public`, and each one gets `@RestrictTo(LIBRARY)`. Members used only inside their own package stay package-private. The known cases are:

- `Passport.Builder`, its constructor and fields, and `Passport`'s constructor or factory taking it. `DgParser` (in `dg`) fills it.
- `MrzDocument.Builder`, its constructor and setters. `Passport.Builder` (root) and `Mrz` use it.
- `Mrz.decodeInto(String, MrzDocument.Builder)`. `DgParser` uses it.
- Static helpers, constants and constructors on moved classes that the readers or `QrScannerView` call, such as `Apdu.ok`, `Apdu.sw`, `Bac.mutualAuthenticate`, `Bac.mrzInfo`, `Bac.BacException`, `EfReader`'s constructor and `read`, the `DgParser.parse*` methods, `Emv.read`, and the `QrScanner` and `PreviewGeometry` entry points.

The compiler gives the definitive list: after the moves, every "not public in …; cannot be accessed from outside package" error marks a member to open.

## Public API changes (breaking)

| Before | After |
|---|---|
| `com.adkhambek.reader.passport.Mrz` | `com.adkhambek.reader.passport.mrz.Mrz` |
| `com.adkhambek.reader.passport.MrzDocument` | `com.adkhambek.reader.passport.mrz.MrzDocument` |
| `com.adkhambek.reader.passport.MrzFormatException` | `com.adkhambek.reader.passport.mrz.MrzFormatException` |
| `com.adkhambek.reader.passport.MrzKey` | `com.adkhambek.reader.passport.mrz.MrzKey` |
| `com.adkhambek.reader.qr.ScannerOverlayView` | `com.adkhambek.reader.qr.view.ScannerOverlayView` |

Nothing has been published outside `mavenLocal`, and 3.0.0 is not released yet, so the version does not change. The sample app and the README examples update their imports.

## Tests

Tests move with the class they test, because they use package-private members:

| Test | Package |
|---|---|
| `ApduTest`, `TlvTest`, `Replay`, `ReplayTest` (both libraries) | `iso7816` |
| `EmvTest` | `card.emv` |
| `BacTest`, `SecureMessagingTest` | `passport.bac` |
| `EfReaderTest`, `Dg2Test` | `passport.dg` |
| `MrzTest`, `MrzKeyTest` | `passport.mrz` |
| `PreviewGeometryTest` | `qr.view` |
| `QrScannerStaleTest` | `qr.camera` |
| `CallTest`, `CardReaderTest`, `PassportReaderTest` | root (unchanged) |

`Replay` becomes `public` (test source set only, so no `@RestrictTo` is needed), so the root reader tests can keep using it. The test count is unchanged and every existing test passes without edits other than `package` and `import` lines.

## Constraints

- No behavioural change. No logic edits beyond visibility modifiers, annotations, `package` and `import` lines.
- Twin rule unchanged: the 7 main twins (`Apdu`, `Tlv`, `Transceiver`, `Call`, `Callback`, `Cancellable`, `ReadException`) and 5 test twins are identical between `card` and `passport` apart from line 2. `Bytes` exists in both but is not a twin.
- No library depends on another. Java only. TAB indentation. SPDX header on every file.
- `./gradlew test lint` is green, and the sample app builds, after each library is converted.
- Use `git mv` for every move, so history follows the files.

## Out of scope

- Renaming any class.
- Splitting or merging any file.
- Moving `Call` out of the root, since it is used only by the reader in the same package.
