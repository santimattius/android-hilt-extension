plugins {
    id("hiltext.android-library")
    id("hiltext.publishing")
}

android {
    namespace = "com.santimattius.android.hilt.coroutines"
}

kotlin {
    explicitApi()
}

dependencies {
    // Public API exposes kotlinx.coroutines types (CoroutineDispatcher) and Dagger types
    // (dagger.Lazy, javax.inject.Provider).
    api(libs.kotlinx.coroutines.core)
    api(libs.hilt.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
