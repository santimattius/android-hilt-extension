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

    // CI smoke run only (-PbenchmarkSmoke=true, used by :microbenchmarkSmokeCi): AndroidX Benchmark
    // refuses to run on an emulator unless that error is suppressed. The run then proves the benchmark
    // executes end to end; emulator timings are not a measurement. Without the property the runner
    // keeps refusing emulators.
    if (providers.gradleProperty("benchmarkSmoke").orNull == "true") {
        defaultConfig {
            testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Gradle Managed Device for the CI smoke run (task :benchmark-micro:smokeReleaseAndroidTest).
        managedDevices.localDevices.create("smoke") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp-atd"
        }
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

// CI smoke entry point: runs the benchmark on the managed device "smoke" with the emulator error
// suppressed. It only proves the benchmark executes end to end; emulator timings are NOT a measurement
// of overhead and are never recorded. Local equivalent: ./gradlew microbenchmarkSmokeCi (see docs/BENCHMARK.md).
tasks.register("microbenchmarkSmokeCi") {
    group = "verification"
    description = "Smoke-runs the microbenchmark on the managed device 'smoke' (emulator errors suppressed; timings are not a measurement)."
    if (providers.gradleProperty("benchmarkSmoke").orNull == "true") {
        dependsOn("smokeReleaseAndroidTest")
    } else {
        doFirst { throw GradleException("Pass -PbenchmarkSmoke=true: the runner refuses emulators otherwise.") }
    }
}
