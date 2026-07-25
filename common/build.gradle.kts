import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.adkhambek.reader.common"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = true
        // Pinned versions — the working set with AGP 8.7.
        disable += "GradleDependency"
    }
}

mavenPublishing {
    // Builds the AAR + sources jar; skip Javadoc (no Kotlin/Dokka here, nothing to generate).
    configure(AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = true,
        publishJavadocJar = false,
    ))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
