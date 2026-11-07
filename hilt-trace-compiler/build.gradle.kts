plugins {
    id("hiltext.jvm-library")
    id("hiltext.publishing")
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(libs.junit)
}
