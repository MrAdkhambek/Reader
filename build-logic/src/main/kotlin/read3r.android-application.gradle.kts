import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

/**
 * Shared configuration for the sample application module.
 *
 * Only the platform/toolchain settings live here — applicationId, version,
 * buildTypes and the app's own lint suppressions stay in app/build.gradle.kts
 * where they can be read next to the manifest they justify.
 */

plugins {
    id("com.android.application")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun version(name: String) = libs.findVersion(name).get().requiredVersion

extensions.configure<BaseAppModuleExtension> {
    compileSdk = version("compileSdk").toInt()

    defaultConfig {
        minSdk = version("minSdk").toInt()
        targetSdk = version("targetSdk").toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        // See read3r.android-library: versions are a deliberately pinned set.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}
