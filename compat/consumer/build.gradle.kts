plugins {
    id("com.android.application")
    // Declared only to pin the Kotlin Gradle plugin version: AGP 9 has built-in Kotlin and
    // otherwise uses its own default. No Compose code is compiled here.
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.santimattius.android.hilt.trace")
}

val hiltVersion = providers.gradleProperty("compatHilt").get()
val libraryVersion = providers.gradleProperty("compatLibraryVersion").get()

android {
    namespace = "com.example.consumer"
    // The lowest compileSdk the published libraries support (androidLibraryCompileSdkVersion).
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}

ksp { arg("hiltTrace.module", ":app") }

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.tracing:tracing:2.0.5")
    implementation("com.santimattius.android:hilt-coroutines-runtime:$libraryVersion")
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")
}
