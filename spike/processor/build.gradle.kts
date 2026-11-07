// Throwaway slice 0.8 probe: a JVM KSP processor that writes META-INF/hilt-trace/<module>.json,
// plus a kctfork test proving the pinned Kotlin/KSP/kctfork combination.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.13")
    testImplementation(libs.junit)
    testImplementation("dev.zacsweers.kctfork:core:0.14.0")
    testImplementation("dev.zacsweers.kctfork:ksp:0.14.0")
}
