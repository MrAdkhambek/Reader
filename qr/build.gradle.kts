import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.adkhambek.reader.qr"
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
        disable += "GradleDependency"
    }
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = true,
        publishJavadocJar = false,
    ))
}

dependencies {
    api(project(":common"))

    api("androidx.camera:camera-core:1.3.4")
    api("androidx.camera:camera-camera2:1.3.4")
    api("androidx.camera:camera-lifecycle:1.3.4")
    api("androidx.camera:camera-view:1.3.4")
    api("androidx.lifecycle:lifecycle-common:2.7.0")

    implementation("androidx.core:core:1.13.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
