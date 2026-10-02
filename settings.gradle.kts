pluginManagement {
    // Convention plugins (read3r.*) that carry the shared Android / publishing setup.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Read3r"
include(
    ":app",
    ":card",
    ":common",
    ":iso7816",
    ":nfc",
    ":qr",
)
