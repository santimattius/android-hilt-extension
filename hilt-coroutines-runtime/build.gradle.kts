plugins {
    id("hiltext.android-library")
    id("hiltext.publishing")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.santimattius.android.hilt.coroutines"

    defaultConfig {
        // Replaces the application with HiltTestApplication for the instrumented Hilt tests.
        testInstrumentationRunner = "com.santimattius.android.hilt.coroutines.HiltTestRunner"
    }

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
    // Public API exposes kotlinx.coroutines types (CoroutineDispatcher) and Dagger types
    // (dagger.Lazy, javax.inject.Provider).
    api(libs.kotlinx.coroutines.core)
    api(libs.hilt.android)

    // Dispatchers.Main needs the Android dispatcher at runtime; the tracing passthrough only
    // delegates, so neither type appears in the public API.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.tracing)

    // KSP only (no kapt): the Hilt compiler publishes the module to consumer components.
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
