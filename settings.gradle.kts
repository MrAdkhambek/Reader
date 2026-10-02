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
        // The sample app uses the published libraries; run publishToMavenLocal first.
        mavenLocal {
            content { includeGroup("com.adkhambek.reader") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Read3r"
include(
    ":app",
    ":card",
    ":passport",
    ":qr",
)
