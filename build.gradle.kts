// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    // KSP and Hilt plugin versions are declared once here; modules apply the ids unversioned.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("hiltext.version-alignment")
}

// Each included build keeps its own libraryVersion default (it does not read this file),
// so the defaults are compared against the root value.
tasks.named<io.github.santimattius.android.hilt.buildlogic.VerifyVersionAlignment>("verifyVersionAlignment") {
    propertyFiles.from(
        file("gradle.properties"),
        file("build-logic/gradle.properties"),
        file("hilt-trace-gradle-plugin/gradle.properties"),
    )
}

// The root check also runs the checks of the included builds that exist in this build.
tasks.named("check") {
    gradle.includedBuilds.forEach { dependsOn(it.task(":check")) }
}

// Deterministic CI entry point for the instrumented tests: boots the Gradle Managed Device "ci"
// (Pixel 6, API 34, aosp-atd; defined in :app and :hilt-coroutines-runtime) and runs every androidTest
// suite of those modules. Needs hardware virtualization (KVM on Linux runners). See docs/VERIFY.md.
tasks.register("instrumentedTestsCi") {
    group = "verification"
    description = "Runs the app and hilt-coroutines-runtime instrumented tests on the managed device 'ci'."
    dependsOn(":app:ciDebugAndroidTest", ":hilt-coroutines-runtime:ciDebugAndroidTest")
}
