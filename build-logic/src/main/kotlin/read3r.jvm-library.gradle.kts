import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension

/**
 * Shared configuration for published *plain Java* library modules (:common).
 *
 * Deliberately not an Android library: a module with no Android API usage ships
 * no manifest, so it cannot merge permissions into a consumer that only wanted
 * one of the readers.
 */

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

extensions.configure<MavenPublishBaseExtension> {
    configure(
        JavaLibrary(
            javadocJar = JavadocJar.None(),
            sourcesJar = true,
        )
    )
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
