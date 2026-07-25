plugins {
    id("read3r.android-library")
}

android {
    namespace = "com.adkhambek.reader.qr"
}

dependencies {
    api(project(":common"))

    // Align every kotlin-stdlib variant on the consumer's classpath. CameraX 1.3
    // pulls kotlin-stdlib-jdk7/jdk8:1.6.21 while any modern androidx artifact
    // pulls kotlin-stdlib:1.8.x — 1.8 folded the jdk7/jdk8 classes into the base
    // stdlib, so the two together are a duplicate-class dex failure.
    //
    // This is `api(platform(...))`, not `implementation`, on purpose: it has to
    // reach whoever depends on the published qr artifact. Declared app-side it
    // would fix only this repo's sample and leave every external consumer to
    // rediscover the same crash.
    api(platform(libs.kotlin.bom))

    // api, not implementation: these types are on the public surface —
    // QrScannerView.getPreviewView() returns a PreviewView and
    // setLifecycleOwner() takes a LifecycleOwner.
    api(libs.camera.core)
    api(libs.camera.camera2)
    api(libs.camera.lifecycle)
    api(libs.camera.view)
    api(libs.lifecycle.common)

    // Internal only — ScanResult carries float[]/int, never a ZXing type.
    implementation(libs.androidx.core)
    implementation(libs.zxing.core)
}
