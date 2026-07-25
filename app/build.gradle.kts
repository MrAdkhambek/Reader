plugins {
    id("com.android.application")
}

android {
    namespace = "com.adkhambek.reader.card"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adkhambek.reader.card"
        minSdk = 21
        targetSdk = 34
        versionCode = 16
        versionName = "2.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        // Treat any remaining warnings as build errors so they can't accumulate.
        warningsAsErrors = true

        // Deliberate design choices, not bugs:
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
            "GradleDependency",
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
    implementation("androidx.activity:activity:1.8.2")

    // Align all kotlin-stdlib variants. CameraX 1.3 pulls kotlin-stdlib-jdk7/jdk8:1.6.21
    // and androidx.activity pulls kotlin-stdlib:1.8.x — 1.8 merged the jdk7/jdk8 classes
    // into the base stdlib, so without the BOM we get a duplicate-class dex error.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
}
