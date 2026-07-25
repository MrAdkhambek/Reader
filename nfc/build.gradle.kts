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
}
