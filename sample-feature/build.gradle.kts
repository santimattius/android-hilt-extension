plugins {
    id("hiltext.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.santimattius.android.sample.feature"
}

ksp {
    arg("hiltTrace.module", ":sample-feature")
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(project(":hilt-trace-compiler"))
}
