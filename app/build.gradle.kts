import com.android.build.api.variant.HasUnitTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("io.github.santimattius.android.hilt.trace")
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project
val libraryGroupId: String by project

android {
    namespace = "io.github.santimattius.android.sample"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        applicationId = "io.github.santimattius.android.sample"
        minSdk = androidMinSdkVersion.toInt()
        targetSdk = androidTargetSdkVersion.toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Workaround (AGP 9.5.0 with Kotlin 2.4.21 Compose plugin): AGP creates a Compose Preview runfiles
        // task for every variant when the Compose compiler plugin is applied, and it fails while the
        // bytecode transform task resolves its dependencies unless Android resources and unit tests are
        // enabled for the variant. The failure is independent of the Hilt Trace plugin.
        unitTests.isIncludeAndroidResources = true
        // androidx.tracing reads android.os.Process while initializing; the demo unit tests call it.
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // Second half of the Compose Preview workaround above: unit tests must exist for every variant.
        (variantBuilder as? HasUnitTestBuilder)?.enableUnitTest = true
    }
}

// The plugin adds the compiler and runtime at its own group and version. This repository intentionally
// substitutes those coordinates with local projects so the sample validates the current source.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("$libraryGroupId:hilt-trace-runtime")).using(project(":hilt-trace-runtime"))
        substitute(module("$libraryGroupId:hilt-trace-compiler")).using(project(":hilt-trace-compiler"))
    }
}

ksp {
    arg("hiltTrace.module", ":app")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":hilt-coroutines-runtime"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // tracing-wire does not expose androidx.tracing on the compile classpath, so declare it explicitly.
    implementation(libs.androidx.tracing)
    debugImplementation(libs.androidx.tracing.wire)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}