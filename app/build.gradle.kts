plugins {
    id("read3r.android-application")
}

android {
    namespace = "com.adkhambek.reader.sample"

    defaultConfig {
        applicationId = "com.adkhambek.reader.sample"
        versionCode = 16
        // Single source of truth with the published library version.
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // On top of the convention plugin's warningsAsErrors — deliberate design
        // choices, not bugs:
        //   OldTargetApi          — pinned to 34. Bumping to 35 introduces real
        //                            behavior changes (edge-to-edge enforcement
        //                            on Android 15) that need device validation.
        //   LockedOrientationActivity / DiscouragedApi — the QR scanner is
        //                            intentionally portrait-only; rotating the
        //                            CameraX preview mid-scan is a UX regression.
        //   UnnecessaryRequiredFeature — this app is fundamentally an NFC reader
        //                            (two of three flows); devices without NFC
        //                            should not be offered the install.
        disable += setOf(
            "OldTargetApi",
            "LockedOrientationActivity",
            "DiscouragedApi",
            "UnnecessaryRequiredFeature",
            // allowBackup is needed for API 21-30 (where dataExtractionRules
            // isn't honored). dataExtractionRules covers 31+. Both are set —
            // the lint complaint is about the attribute being marked
            // deprecated on newer platforms, but we still need it for older.
            "DataExtractionRules"
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":nfc"))
    implementation(project(":qr"))

    // ComponentActivity = the minimum AndroidX dep needed for CameraX's LifecycleOwner.
    implementation(libs.androidx.activity)

    // The kotlin-stdlib alignment CameraX needs is published by :qr itself, so
    // external consumers get it too — see qr/build.gradle.kts.
}
