plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.nfc"
}

dependencies {
    // api: Result and the IdCard/MRZ model appear in this module's public
    // signatures, so consumers need them on their compile classpath.
    api(project(":common"))
    // api: Iso7816.StdTag / Response and NfcManager appear in this module's
    // public signatures, so consumers need them on their compile classpath.
    api(project(":iso7816"))
}
