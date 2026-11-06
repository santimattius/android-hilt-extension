// Throwaway slice 0.8 probe: an Android library whose KSP output must reach its AAR.
plugins {
    alias(libs.plugins.android.library)
    id("com.google.devtools.ksp")
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project

android {
    namespace = "spikelib"
    compileSdk = androidTargetSdkVersion.toInt()
    defaultConfig { minSdk = androidMinSdkVersion.toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp { arg("hiltTrace.module", ":spike:lib") }

dependencies {
    ksp(project(":spike:processor"))
}
