plugins {
    id("com.android.application")
    // Declared only to pin the Kotlin Gradle plugin version: AGP 9 has built-in Kotlin and
    // otherwise uses its own default. No Compose code is compiled here.
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.github.santimattius.android.hilt.trace")
}

val hiltVersion = providers.gradleProperty("compatHilt").get()
val libraryVersion = providers.gradleProperty("compatLibraryVersion").get()
// The libraries declare minSdk 24 (gradle.properties); compatMinSdk lets the matrix probe other values.
val consumerMinSdk = providers.gradleProperty("compatMinSdk").orElse("24").get().toInt()
// API 23 evidence probe: tools:overrideLibrary for the hilt-trace libraries, applied only to this consumer
// (src/probe/AndroidManifest.xml, merged into the debug variant). Never changes the published minSdk.
val overrideLibraryProbe = providers.gradleProperty("compatOverrideLibrary").orNull == "true"

android {
    namespace = "com.example.consumer"
    // The lowest compileSdk the published libraries support (androidLibraryCompileSdkVersion).
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.consumer"
        minSdk = consumerMinSdk
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (overrideLibraryProbe) sourceSets.getByName("debug").manifest.srcFile("src/probe/AndroidManifest.xml")
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
    implementation("io.github.santimattius.android:hilt-coroutines-runtime:$libraryVersion")
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
