// Isolated Jetpack Microbenchmark module. It is never published and nothing depends on it.
plugins {
    id("hiltext.android-library")
}

android {
    namespace = "io.github.santimattius.android.hilt.benchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    // Benchmarks must run non-debuggable.
    testBuildType = "release"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":hilt-trace-runtime"))
    implementation(libs.androidx.tracing)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Deterministic entry points.
// No device needed: compiles and packages the benchmark APK.
tasks.register("assembleMicrobenchmark") {
    group = "verification"
    description = "Compiles and packages the disabled-category microbenchmark (no device needed)."
    dependsOn("assembleReleaseAndroidTest")
}

// Needs a connected device (prefer a physical one; see docs/BENCHMARK.md).
tasks.register("runMicrobenchmark") {
    group = "verification"
    description = "Runs the disabled-category microbenchmark on a connected device."
    dependsOn("connectedReleaseAndroidTest")
}
