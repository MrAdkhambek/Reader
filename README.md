<p align="center">
  <img src="docs/assets/logo.svg" alt="Read3r" width="120">
</p>

<h1 align="center">Read3r</h1>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.adkhambek.reader/card"><img src="https://img.shields.io/maven-central/v/com.adkhambek.reader/card.svg?label=card" alt="card on Maven Central"></a>
  <a href="https://central.sonatype.com/artifact/com.adkhambek.reader/passport"><img src="https://img.shields.io/maven-central/v/com.adkhambek.reader/passport.svg?label=passport" alt="passport on Maven Central"></a>
  <a href="https://central.sonatype.com/artifact/com.adkhambek.reader/qr"><img src="https://img.shields.io/maven-central/v/com.adkhambek.reader/qr.svg?label=qr" alt="qr on Maven Central"></a>
</p>

Three independent Android libraries: read contactless **bank cards**, ICAO 9303 **passports and ID cards**, and **QR codes**. Each is plain Java, and none depends on another — add only what you use.

| Artifact | Reads | Depends on |
|---|---|---|
| `com.adkhambek.reader:card:0.0.1` | EMV bank cards over NFC | androidx.annotation |
| `com.adkhambek.reader:passport:0.0.1` | ePassports / eMRTD ID cards over NFC, MRZ text | androidx.annotation |
| `com.adkhambek.reader:qr:0.0.1` | QR codes with the camera | CameraX, ZXing, lifecycle-common, androidx.annotation, the kotlin-bom platform |

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
    runOnUiThread(() -> {
        if (pending != null) pending.cancel();   // drop the previous read first
        pending = reader.read(tag, new Callback<Card>() {
            @Override public void onSuccess(Card card) { show(card); }
            @Override public void onError(ReadException e) { show(e.reason()); }
        });
    });
}

@Override protected void onDestroy() {
    if (pending != null) pending.cancel();   // no callback after this
    super.onDestroy();
}
```

A passport needs the three MRZ fields printed on its data page. `MrzKey`, `Mrz`, `MrzDocument` and `MrzFormatException` live in `com.adkhambek.reader.passport.mrz`:

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
- **`Passport`**: `mrz()` (DG1), `personalDetails()` (DG11), `documentDetails()` (DG12), `nationalData()` (DG13), `photo()` (DG2, JPEG or JPEG 2000), `presentDataGroups()`. `nationalData()` and `presentDataGroups()` are never null (empty when absent); the other optional groups are null when absent.

Elementary files are read up to 32 KB; a DG2 photo larger than that comes back null.

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

## Security

- **PAN and cardholder name** put you in PCI scope if you transmit or store them. The libraries never log them.
- **MRZ data and the DG2 photo** are high-value identity and biometric data. `toString()` on `Passport`, `MrzDocument`, `PersonalDetails`, `DocumentDetails` and `CardApp` is redacted (a PAN shows only its last four digits); the accessors are not.
- **`Card.uid`** is the tag's NFC UID. Many cards do not randomise it.
- No library performs network I/O.

## License

Apache 2.0. See [LICENSE](LICENSE).
