plugins {
    // Plain jar, not an Android library: nothing here touches the Android SDK,
    // so the module ships no manifest and cannot merge an android.permission.NFC
    // entry into a consumer that only wanted the QR scanner.
    id("read3r.jvm-library")
}
