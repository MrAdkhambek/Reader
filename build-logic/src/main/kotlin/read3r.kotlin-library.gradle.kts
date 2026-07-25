import com.android.build.gradle.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared configuration for the published Kotlin -ktx modules.
 *
 * These are the only modules that depend on the Kotlin stdlib and coroutines;
 * the Java cores stay free of both. explicitApi() is on because this is a
 * published API surface — every public declaration must state its visibility
 * and return type.
 */

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = true
        // See read3r.android-library: versions are a deliberately pinned set.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

extensions.configure<MavenPublishBaseExtension> {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )
}

dependencies {
    "api"(libs.findLibrary("coroutines.core").get())
    "implementation"(libs.findLibrary("coroutines.android").get())
    "testImplementation"(libs.findLibrary("junit").get())
}
