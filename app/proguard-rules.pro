# Strip Log.d / Log.v from release.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Public API of the reader library modules (common / nfc / qr) the app consumes.
# Keep the names stable under R8 — the modules are published as AARs.
# NOTE: these paths must track the real packages; stale paths match nothing and
# silently defeat the keep (the classes then survive only by reachability).
-keep class com.adkhambek.reader.common.Result { *; }
-keep class com.adkhambek.reader.common.mrz.** { *; }

-keep class com.adkhambek.reader.nfc.common.NfcManager { *; }
-keep class com.adkhambek.reader.nfc.card.SPEC { *; }
-keep class com.adkhambek.reader.nfc.card.SPEC$CUR { *; }
-keep class com.adkhambek.reader.nfc.card.bean.** { *; }
-keep class com.adkhambek.reader.nfc.card.reader.ReaderManager { *; }
-keep interface com.adkhambek.reader.nfc.card.reader.ReaderListener { *; }
-keep class com.adkhambek.reader.nfc.id.MrzKey { *; }
-keep class com.adkhambek.reader.nfc.id.IdReaderManager { *; }
-keep interface com.adkhambek.reader.nfc.id.IdReaderListener { *; }
-keep class com.adkhambek.reader.nfc.id.dg.Dg2 { *; }
-keep class com.adkhambek.reader.nfc.id.dg.Dg2$Image { *; }

-keep class com.adkhambek.reader.qr.common.ScanResult { *; }
-keep class com.adkhambek.reader.qr.id.** { *; }
-keep class com.adkhambek.reader.qr.view.QrScanner { *; }
-keep interface com.adkhambek.reader.qr.view.QrScanListener { *; }
-keep class com.adkhambek.reader.qr.view.QrScannerView { *; }
-keep interface com.adkhambek.reader.qr.view.QrScannerView$Listener { *; }
-keep class com.adkhambek.reader.qr.view.ScannerOverlayView { *; }
