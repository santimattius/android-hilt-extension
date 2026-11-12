plugins {
    id("hiltext.android-library")
    id("hiltext.publishing")
}

android {
    namespace = "com.santimattius.android.hilt.trace"

    testOptions {
        // androidx.tracing reads android.os.Process (pid/tid) while initializing; the unit-test
        // android.jar stubs would throw without default return values.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // Tracing 1.x and 2.x share one coordinate: this `api` dependency upgrades consumers to 2.x.
    api(libs.androidx.tracing)

    testImplementation(libs.junit)
}
