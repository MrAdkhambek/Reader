# Inner Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split each library's flat root package into sub-packages by function, with no change in behaviour.

**Architecture:** Each task converts one library. The steps are: `git mv` the files, rewrite the `package` lines, add imports, then let the compiler name every member that now crosses a package boundary. Each such member becomes `public` with `@RestrictTo(RestrictTo.Scope.LIBRARY)`, except the types that are public API anyway. Tests move with the class they test.

**Tech Stack:** Java 17, Android Gradle Plugin 8.7.3, `androidx.annotation` 1.8.2, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-02-inner-packages-design.md`

## Global Constraints

- No behavioural change. The only edits allowed are: `package` and `import` lines, visibility modifiers, `@RestrictTo` annotations with their import, and Javadoc `{@link}` targets that the move breaks.
- A member opened only because of the move gets `@RestrictTo(RestrictTo.Scope.LIBRARY)`. The public API types listed in the spec (including the moved `mrz.*` and `view.ScannerOverlayView`) do not get it.
- A class or member is opened only when the compiler reports it is inaccessible. Nothing is opened pre-emptively.
- Twin rule: the 7 main twins (`Apdu`, `Tlv`, `Transceiver`, `Call`, `Callback`, `Cancellable`, `ReadException`) and 5 test twins (`ApduTest`, `TlvTest`, `CallTest`, `Replay`, `ReplayTest`) stay identical between `card` and `passport` apart from line 2 (`package …`).
- No library depends on another. Java only. **TAB indentation in Java; 4 spaces in `.kts`.** Every Java file starts with `/* SPDX-License-Identifier: Apache-2.0 */`.
- Use `git mv` for every move.
- `./gradlew test lint :app:assembleDebug` is green at the end of every task. Run it **without** `--offline`.
- The user has uncommitted edits in `app/src/main/java/com/adkhambek/reader/sample/IdInputActivity.java` and `MainActivity.java`. Never stage them. Never use `git add -A`, `git add .` or `git add app`.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. **A moved helper becomes callable from an app with no warning.** Every newly public member must carry `@RestrictTo(LIBRARY)`, so lint rejects any use from an app. Each task's Step "audit" greps for public members that lack the annotation.
2. **Lint must see the annotation.** If `androidx.annotation` stays `implementation`, consumers' lint never sees `@RestrictTo`. Task 1 and Task 2 change it to `api`, and Task 3 adds it to `qr`.
3. **Twin drift.** Moving the twins into `iso7816` in both libraries must keep them identical apart from line 2. Task 2 ends with the twin diff check.
4. **The test count silently drops** because a moved test's package no longer matches its directory, and Gradle skips it. Each task records the test count before and after, and they must be equal.
5. **Redaction is lost in a move.** `Passport.toString`, `MrzDocument.toString`, `PersonalDetails`/`DocumentDetails`/`CardApp` `toString` stay byte-identical. The only edits allowed to these files are `package`, `import` and visibility lines.

---

### Task 1: `card` sub-packages

**Files:**
- Move (main, `card/src/main/java/com/adkhambek/reader/card/`): `Apdu`, `Tlv`, `Transceiver`, `Bytes` → `iso7816/`; `Emv` → `emv/`
- Move (test, `card/src/test/java/com/adkhambek/reader/card/`): `ApduTest`, `TlvTest`, `Replay`, `ReplayTest` → `iso7816/`; `EmvTest` → `emv/`
- Modify: `CardReader.java`, `CardApp.java` (imports only if needed), `CardReaderTest.java` (imports), `card/build.gradle.kts`

**Interfaces:**
- Produces: package `com.adkhambek.reader.card.iso7816` containing `Apdu`, `Tlv`, `Transceiver`, `Bytes` and test-side `Replay`. The public root API is unchanged.

- [ ] **Step 1: Record the baseline test count**

Run: `./gradlew :card:testDebugUnitTest -q && python3 -c "import glob,re;print(sum(int(re.search(r'tests=\"(\d+)\"',open(f).read()).group(1)) for f in glob.glob('card/build/test-results/testDebugUnitTest/*.xml')))"`
Expected: a number N. Write it in the report.

- [ ] **Step 2: Move the files**

```bash
B=com/adkhambek/reader/card
M=card/src/main/java/$B; T=card/src/test/java/$B
mkdir -p $M/iso7816 $M/emv $T/iso7816 $T/emv
for f in Apdu Tlv Transceiver Bytes; do git mv $M/$f.java $M/iso7816/$f.java; done
git mv $M/Emv.java $M/emv/Emv.java
for f in ApduTest TlvTest Replay ReplayTest; do git mv $T/$f.java $T/iso7816/$f.java; done
git mv $T/EmvTest.java $T/emv/EmvTest.java
perl -pi -e 's/^package com\.adkhambek\.reader\.card;/package com.adkhambek.reader.card.iso7816;/' $M/iso7816/*.java $T/iso7816/*.java
perl -pi -e 's/^package com\.adkhambek\.reader\.card;/package com.adkhambek.reader.card.emv;/' $M/emv/*.java $T/emv/*.java
```

- [ ] **Step 3: Add imports**

Each file gets imports for the types it uses from other packages, inserted after the `package` line and before the existing imports, keeping the file's import order (`android.*`, `androidx.*`, `com.*`, then `java.*`):
- `CardReader.java`: `import com.adkhambek.reader.card.emv.Emv;`, `import com.adkhambek.reader.card.iso7816.Apdu;`, `import com.adkhambek.reader.card.iso7816.Bytes;`, `import com.adkhambek.reader.card.iso7816.Transceiver;`. Add only those it actually references.
- `emv/Emv.java`: `import com.adkhambek.reader.card.CardApp;`, `import com.adkhambek.reader.card.Currency;` (if referenced), `import com.adkhambek.reader.card.iso7816.Apdu;`, `import com.adkhambek.reader.card.iso7816.Bytes;`, `import com.adkhambek.reader.card.iso7816.Tlv;`.
- `CardReaderTest.java`: `import com.adkhambek.reader.card.iso7816.Replay;`.
- `emv/EmvTest.java`: imports for whatever root and `iso7816` types it references.

To find what each file references, run `grep -nwE "Apdu|Tlv|Transceiver|Bytes|Emv|Replay|CardApp|Card|Currency|ReadException" <file>`.

- [ ] **Step 4: Compile and open what the compiler names**

Run: `./gradlew :card:compileDebugUnitTestJavaWithJavac 2>&1 | grep -E "error:" | sort -u`
Expected: errors of the form `X is not public in Y; cannot be accessed from outside package` or `X() is not public …`.

For each error, make the named class, constructor, method or field `public` and annotate it:

```java
import androidx.annotation.RestrictTo;
…
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Apdu {
```

The annotation goes on the opened class and on each opened member. A member of a class that was already public gets it too, for example a `CardApp` constructor that `Emv` now calls from `emv/`. Test classes (`Replay`) become `public` without `@RestrictTo`, because test sources are not published. Repeat until the compile is clean.

- [ ] **Step 5: Make `androidx.annotation` visible to consumers' lint**

In `card/build.gradle.kts`, change `implementation(libs.androidx.annotation)` to `api(libs.androidx.annotation)`, and set the comment above it to:

```kotlin
    // api: @RestrictTo on the helper sub-packages must reach consumers' lint.
```

- [ ] **Step 6: Audit for unannotated openings**

Run: `git diff -U0 HEAD -- card/src/main | grep -E "^\+.*\bpublic\b" | grep -v "^+++"`
Expected: every listed line is either preceded in the file by `@RestrictTo(RestrictTo.Scope.LIBRARY)` or belongs to a class that was already public API before this task (`CardReader`, `Card`, `CardApp`, `Currency`, `Callback`, `Cancellable`, `ReadException`). For opened members of those public API classes, the annotation is required. Fix any line that does not satisfy this.

- [ ] **Step 7: Run the full verification**

Run: `./gradlew test lint :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Then re-run Step 1's count command. Expected: the same N.

- [ ] **Step 8: Commit**

```bash
git add card/
git commit -m "refactor(card): split into iso7816 and emv sub-packages

Helpers used across packages are public with @RestrictTo(LIBRARY);
androidx.annotation is now api so consumers' lint enforces it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `passport` sub-packages

**Files:**
- Move (main, `passport/src/main/java/com/adkhambek/reader/passport/`): `Apdu`, `Tlv`, `Transceiver`, `Bytes` → `iso7816/`; `Mrz`, `MrzDocument`, `MrzFormatException`, `MrzKey` → `mrz/`; `Bac`, `Iso9797Mac`, `SecureMessaging` → `bac/`; `EfReader`, `DgParser`, `Dg2` → `dg/`
- Move (test, `passport/src/test/java/com/adkhambek/reader/passport/`): `ApduTest`, `TlvTest`, `Replay`, `ReplayTest` → `iso7816/`; `MrzTest`, `MrzKeyTest` → `mrz/`; `BacTest`, `SecureMessagingTest` → `bac/`; `EfReaderTest`, `Dg2Test` → `dg/`
- Modify: `PassportReader.java`, `Passport.java`, `PassportReaderTest.java` (imports, visibility), `passport/build.gradle.kts`
- Modify (sample app): `app/src/main/java/com/adkhambek/reader/sample/IdReaderActivity.java`, `QrScannerActivity.java` (imports of `Mrz`, `MrzDocument`, `MrzKey`). `IdInputActivity.java` only if it imports a moved type. If it does, edit only the import line and leave it unstaged; note this in the report.

**Interfaces:**
- Consumes: nothing from Task 1. The libraries are independent, but the `iso7816` layout must mirror Task 1 exactly.
- Produces: public `com.adkhambek.reader.passport.mrz.{Mrz, MrzDocument, MrzFormatException, MrzKey}`. `PassportReader.read(Tag, MrzKey)` now takes `com.adkhambek.reader.passport.mrz.MrzKey`.

- [ ] **Step 1: Record the baseline test count**

Run: `./gradlew :passport:testDebugUnitTest -q && python3 -c "import glob,re;print(sum(int(re.search(r'tests=\"(\d+)\"',open(f).read()).group(1)) for f in glob.glob('passport/build/test-results/testDebugUnitTest/*.xml')))"`
Expected: a number N. Write it in the report.

- [ ] **Step 2: Move the files**

```bash
B=com/adkhambek/reader/passport
M=passport/src/main/java/$B; T=passport/src/test/java/$B
mkdir -p $M/iso7816 $M/mrz $M/bac $M/dg $T/iso7816 $T/mrz $T/bac $T/dg
mv_pkg() { sub=$1; shift; for f in "$@"; do
  if [ -f $M/$f.java ]; then git mv $M/$f.java $M/$sub/$f.java; fi
  if [ -f $T/$f.java ]; then git mv $T/$f.java $T/$sub/$f.java; fi
done
perl -pi -e "s/^package com\.adkhambek\.reader\.passport;/package com.adkhambek.reader.passport.$sub;/" $M/$sub/*.java $T/$sub/*.java 2>/dev/null; }
mv_pkg iso7816 Apdu Tlv Transceiver Bytes ApduTest TlvTest Replay ReplayTest
mv_pkg mrz Mrz MrzDocument MrzFormatException MrzKey MrzTest MrzKeyTest
mv_pkg bac Bac Iso9797Mac SecureMessaging BacTest SecureMessagingTest
mv_pkg dg EfReader DgParser Dg2 EfReaderTest Dg2Test
```

- [ ] **Step 3: Add imports**

For each file under `passport/src`, find which moved types it references:
`grep -nwE "Apdu|Tlv|Transceiver|Bytes|Mrz|MrzDocument|MrzFormatException|MrzKey|Bac|Iso9797Mac|SecureMessaging|EfReader|DgParser|Dg2|Replay|Passport|PersonalDetails|DocumentDetails|Photo|PhotoFormat|ReadException" <file>`

For each reference to a type now in a different package, add its import. The known cross-package references are:
- `PassportReader` → `bac.Bac`, `bac.SecureMessaging`, `dg.EfReader`, `dg.DgParser`, `iso7816.Apdu`, `iso7816.Transceiver`, `mrz.MrzKey`
- `Passport` → `mrz.MrzDocument`
- `dg.DgParser` → `Passport`, `PersonalDetails`, `DocumentDetails`, `iso7816.Tlv`, `mrz.Mrz`
- `dg.Dg2` → `Photo`, `PhotoFormat`, `iso7816.Tlv`
- `dg.EfReader` → `iso7816.Transceiver`
- `bac.SecureMessaging` → `iso7816.Apdu`, `iso7816.Bytes`
- `bac.Bac` → `iso7816.Apdu` (if referenced)
- the tests → whatever the grep shows, including `iso7816.Replay` from `PassportReaderTest`, `dg.EfReaderTest` and `bac.SecureMessagingTest`

The list is a guide. Trust the grep and the compiler.

- [ ] **Step 4: Compile and open what the compiler names**

Run: `./gradlew :passport:compileDebugUnitTestJavaWithJavac 2>&1 | grep -E "error:" | sort -u`

Apply the same rule as Task 1 Step 4: each named class or member becomes `public` with `@RestrictTo(RestrictTo.Scope.LIBRARY)` (`import androidx.annotation.RestrictTo;`). Expect at least the following:
- `Passport.Builder`: the class, its no-arg constructor, its fields `mrz`, `nationalData`, `personalDetails`, `documentDetails`, `photo`, `presentDataGroups`, and its `build()` method if it has one.
- `MrzDocument.Builder`: the class, its constructor and the setters `Mrz` calls.
- `Mrz.decodeInto(String, MrzDocument.Builder)`.
- `Bac.BacException`, `Bac.mutualAuthenticate`, `Bac.mrzInfo`; `SecureMessaging`'s constructor and `transceive`; `EfReader`'s constructors and `read`; `DgParser.parseCom`/`parseDg1`/`parseDg11`/`parseDg12`/`parseDg13`; the `Dg2` entry point; and the `PersonalDetails`/`DocumentDetails`/`Photo` constructors if `DgParser`/`Dg2` call them and they are not already public.
- The `PassportReader.FID_*` constants, if a moved test references them.

`mrz.Mrz`, `mrz.MrzDocument`, `mrz.MrzFormatException` and `mrz.MrzKey` are public API: they keep `public` on their public members without `@RestrictTo`. Only their opened builder members and `decodeInto` get the annotation. Test classes (`Replay`) become `public` without `@RestrictTo`. Repeat until the compile is clean.

- [ ] **Step 5: Update the sample app**

Run: `grep -rnE "import com\.adkhambek\.reader\.passport\.(Mrz|MrzDocument|MrzFormatException|MrzKey);" app/src`

In each hit, rewrite the import to `com.adkhambek.reader.passport.mrz.<Type>`:

```bash
perl -pi -e 's/import com\.adkhambek\.reader\.passport\.(Mrz|MrzDocument|MrzFormatException|MrzKey);/import com.adkhambek.reader.passport.mrz.$1;/' <files from the grep>
```

Then re-sort that import block alphabetically if the file's existing order requires it.

- [ ] **Step 6: Make `androidx.annotation` visible to consumers' lint**

In `passport/build.gradle.kts`, change `implementation(libs.androidx.annotation)` to `api(libs.androidx.annotation)`, with this comment:

```kotlin
    // api: @RestrictTo on the helper sub-packages must reach consumers' lint.
```

- [ ] **Step 7: Audit for unannotated openings and twin drift**

Run: `git diff -U0 HEAD -- passport/src/main | grep -E "^\+.*\bpublic\b" | grep -v "^+++"`
Expected: every line is annotated as in Task 1 Step 6. The exceptions are the moved public API in `mrz/` and the classes that were already public API.

Run the twin check:

```bash
for f in Apdu Tlv Transceiver Call Callback Cancellable ReadException; do
  a=$(find card/src/main -name $f.java); b=$(find passport/src/main -name $f.java)
  diff <(tail -n +3 $a) <(tail -n +3 $b) || echo "DRIFT: $f"
done
for f in ApduTest TlvTest CallTest Replay ReplayTest; do
  a=$(find card/src/test -name $f.java); b=$(find passport/src/test -name $f.java)
  diff <(tail -n +3 $a) <(tail -n +3 $b) || echo "DRIFT: $f"
done
```

Expected: no output. If a twin drifts because Task 1 opened a member that Task 2 did not need (or the reverse), apply the same opening to the other copy, and edit card in a follow-up commit within this task.

- [ ] **Step 8: Run the full verification**

Run: `./gradlew test lint :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Re-run Step 1's count command. Expected: the same N.

- [ ] **Step 9: Commit**

```bash
git add passport/ app/src/main/java/com/adkhambek/reader/sample/IdReaderActivity.java app/src/main/java/com/adkhambek/reader/sample/QrScannerActivity.java
git status --short   # only IdInputActivity.java / MainActivity.java may remain modified
git commit -m "refactor(passport): split into iso7816, mrz, bac and dg sub-packages

BREAKING CHANGE: Mrz, MrzDocument, MrzFormatException and MrzKey move to
com.adkhambek.reader.passport.mrz.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Also stage the card twin files if Step 7 changed them.

---

### Task 3: `qr` sub-packages, README and spec addendum

**Files:**
- Move (main, `qr/src/main/java/com/adkhambek/reader/qr/`): `QrScanner`, `QrAnalyzer` → `camera/`; `ScannerOverlayView`, `PreviewGeometry` → `view/`
- Move (test, `qr/src/test/java/com/adkhambek/reader/qr/`): `QrScannerStaleTest` → `camera/`; `PreviewGeometryTest` → `view/`
- Modify: `QrScannerView.java`, `qr/build.gradle.kts`, `app/src/main/java/com/adkhambek/reader/sample/QrScannerActivity.java` (only if it imports `ScannerOverlayView`), `README.md`, `docs/superpowers/specs/2026-10-02-three-library-design.md`

**Interfaces:**
- Produces: public `com.adkhambek.reader.qr.view.ScannerOverlayView`. `QrScannerView.getScannerOverlay()` returns it.

- [ ] **Step 1: Record the baseline test count**

Run: `./gradlew :qr:testDebugUnitTest -q && python3 -c "import glob,re;print(sum(int(re.search(r'tests=\"(\d+)\"',open(f).read()).group(1)) for f in glob.glob('qr/build/test-results/testDebugUnitTest/*.xml')))"`
Expected: a number N.

- [ ] **Step 2: Move the files**

```bash
B=com/adkhambek/reader/qr
M=qr/src/main/java/$B; T=qr/src/test/java/$B
mkdir -p $M/camera $M/view $T/camera $T/view
git mv $M/QrScanner.java $M/camera/QrScanner.java
git mv $M/QrAnalyzer.java $M/camera/QrAnalyzer.java
git mv $M/ScannerOverlayView.java $M/view/ScannerOverlayView.java
git mv $M/PreviewGeometry.java $M/view/PreviewGeometry.java
git mv $T/QrScannerStaleTest.java $T/camera/QrScannerStaleTest.java
git mv $T/PreviewGeometryTest.java $T/view/PreviewGeometryTest.java
perl -pi -e 's/^package com\.adkhambek\.reader\.qr;/package com.adkhambek.reader.qr.camera;/' $M/camera/*.java $T/camera/*.java
perl -pi -e 's/^package com\.adkhambek\.reader\.qr;/package com.adkhambek.reader.qr.view;/' $M/view/*.java $T/view/*.java
```

- [ ] **Step 3: Add imports**

- `QrScannerView.java`: `import com.adkhambek.reader.qr.camera.QrScanner;`, `import com.adkhambek.reader.qr.view.ScannerOverlayView;`
- `camera/QrScanner.java` and `camera/QrAnalyzer.java`: `import com.adkhambek.reader.qr.QrCode;`
- `view/ScannerOverlayView.java`: nothing new, because `PreviewGeometry` is in the same package.
- `app/…/QrScannerActivity.java`: if it imports `com.adkhambek.reader.qr.ScannerOverlayView`, change the import to `…qr.view.ScannerOverlayView`.

Check with `grep -nwE "QrScanner|QrAnalyzer|ScannerOverlayView|PreviewGeometry|QrCode" <file>`.

- [ ] **Step 4: Compile and open what the compiler names**

Run: `./gradlew :qr:compileDebugUnitTestJavaWithJavac 2>&1 | grep -E "error:" | sort -u`

Use the same rule as Task 1 Step 4. Expect at least the following:
- `QrScanner`: the class, its constructor, and `start`/`stop`/`resume`/`shutdown`, plus the `Sink` callback type if it has one.
- `QrCode`'s constructor, if `QrAnalyzer` builds it and it is not already public. `QrCode` is a public record, so its canonical constructor is already public.

`ScannerOverlayView` is public API and gets no annotation. Members opened on it for `QrScannerView` do get the annotation.

- [ ] **Step 5: Add `androidx.annotation` to qr**

In `qr/build.gradle.kts`, in the `dependencies` block after `api(libs.lifecycle.common)`, add:

```kotlin

    // api: @RestrictTo on the helper sub-packages must reach consumers' lint.
    api(libs.androidx.annotation)
```

- [ ] **Step 6: Audit**

Run: `git diff -U0 HEAD -- qr/src/main | grep -E "^\+.*\bpublic\b" | grep -v "^+++"`
Expected: every opened line is annotated, except `ScannerOverlayView`'s existing public surface.

- [ ] **Step 7: README and spec addendum**

In `README.md`, update every code sample and type reference:
- `import com.adkhambek.reader.passport.MrzKey;` becomes `import com.adkhambek.reader.passport.mrz.MrzKey;`, and the same for `Mrz`, `MrzDocument` and `MrzFormatException`.
- Any `com.adkhambek.reader.qr.ScannerOverlayView` becomes `com.adkhambek.reader.qr.view.ScannerOverlayView`.
- Find them with `grep -nE "Mrz|ScannerOverlayView|import com\.adkhambek" README.md`. If samples use simple names without imports, add one line under the passport section: `MRZ types live in com.adkhambek.reader.passport.mrz.`

In `docs/superpowers/specs/2026-10-02-three-library-design.md`, append after the D3 row's table (the decisions table) a blockquote line:

```markdown
> **Amended 2026-10-02:** D3 is superseded by `2026-10-02-inner-packages-design.md` P1–P2. Helpers now live in function sub-packages, public with `@RestrictTo(LIBRARY)`.
```

- [ ] **Step 8: Run the full verification**

Run: `./gradlew test lint :app:assembleDebug publishToMavenLocal`
Expected: BUILD SUCCESSFUL. The Step 1 count is still N.

Run: `grep -h "<artifactId>annotation</artifactId>" -A2 ~/.m2/repository/com/adkhambek/reader/{card,passport,qr}/3.0.0/*.pom | grep -c "<scope>compile</scope>"`
Expected: `3`. This shows `androidx.annotation` reaches consumers in all three POMs.

- [ ] **Step 9: Commit**

```bash
git add qr/ README.md docs/superpowers/specs/2026-10-02-three-library-design.md
git add app/src/main/java/com/adkhambek/reader/sample/QrScannerActivity.java   # only if Step 3 changed it
git status --short   # only IdInputActivity.java / MainActivity.java may remain modified
git commit -m "refactor(qr): split into camera and view sub-packages

BREAKING CHANGE: ScannerOverlayView moves to com.adkhambek.reader.qr.view.
README and the three-library spec record the new layout.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
