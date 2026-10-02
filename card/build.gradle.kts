plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.card"
}

dependencies {
    // api: @RestrictTo on the helper sub-packages must reach consumers' lint.
    api(libs.androidx.annotation)
}
