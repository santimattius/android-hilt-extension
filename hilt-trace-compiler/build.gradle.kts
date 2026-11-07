plugins {
    id("hiltext.jvm-library")
    id("hiltext.publishing")
}

kotlin {
    explicitApi()
}

dependencies {
    // Supplied by the KSP Gradle plugin at processing time; never bundled into the artifact.
    compileOnly(libs.ksp.api)

    testImplementation(libs.junit)
    testImplementation(libs.ksp.api)
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    // Real Dagger and Hilt annotations (jar artifacts) for the compiled fixtures.
    testImplementation(libs.dagger)
    testImplementation(libs.hilt.core)
}
