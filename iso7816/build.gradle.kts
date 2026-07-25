plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.iso7816"
    // ReplayChannel is published as a test fixture so :emv and :emrtd can
    // replay recorded card transcripts without duplicating the fake.
    testFixtures {
        enable = true
    }
}

dependencies {
    testImplementation(testFixtures(project(":iso7816")))
}
