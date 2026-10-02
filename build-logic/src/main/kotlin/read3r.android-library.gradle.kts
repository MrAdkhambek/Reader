import com.android.build.gradle.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension

/**
 * Shared configuration for every published Android library module (:card, :passport, :qr).
 *
 * Modules keep only what is genuinely theirs: `namespace` and `dependencies`.
 * Their POM coordinates come from each module's own gradle.properties
 * (POM_ARTIFACT_ID / POM_NAME / POM_DESCRIPTION); everything shared lives in
 * the root gradle.properties.
 */

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun version(name: String) = libs.findVersion(name).get().requiredVersion

extensions.configure<LibraryExtension> {
    compileSdk = version("compileSdk").toInt()

    defaultConfig {
        minSdk = version("minSdk").toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Tests are plain JUnit against pure logic — no Robolectric. Stubbed
        // android.jar methods return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // New warnings fail the build rather than accumulating.
        warningsAsErrors = true
        // Versions are pinned in gradle/libs.versions.toml as a working set;
        // "a newer version exists" is not a build error. AndroidGradlePluginVersion
        // is the same check for AGP itself — it only started firing once the
        // version moved into the catalog where lint can see it.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

extensions.configure<MavenPublishBaseExtension> {
    // AAR + sources jar; no Javadoc jar (no Kotlin/Dokka here, nothing to generate).
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
