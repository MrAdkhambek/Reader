plugins {
    `kotlin-dsl`
}

dependencies {
    // Precompiled script plugins can only `id(...)` a plugin that is on this
    // project's compile classpath, so the convention plugins pull AGP and the
    // publish plugin in as ordinary dependencies.
    implementation(libs.android.gradlePlugin)
    implementation(libs.mavenPublish.gradlePlugin)
}
