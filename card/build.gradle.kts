plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.card"
}

dependencies {
    // @WorkerThread on the blocking read. CLASS retention: consumers do not
    // need it on their compile classpath, so implementation is enough.
    implementation(libs.androidx.annotation)
}
