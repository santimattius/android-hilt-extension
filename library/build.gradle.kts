plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project

val libraryGroupId: String by project
val libraryArtifactId: String by project
val libraryVersion: String by project

android {
    namespace = "com.santimattius.android.hilt"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        minSdk = androidMinSdkVersion.toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(libraryGroupId, libraryArtifactId, libraryVersion)

    pom {
        name.set(project.provider { project.name })
        description.set(findProperty("POM_DESCRIPTION") as String? ?: "Android Hilt Extension")
        inceptionYear.set(findProperty("POM_INCEPTION_YEAR") as String? ?: "2026")
        url.set(findProperty("POM_URL") as String? ?: "https://github.com/santimattius/android-hilt-extension/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(findProperty("POM_DEVELOPER_ID") as String? ?: "santimattius")
                name.set(findProperty("POM_DEVELOPER_NAME") as String? ?: "Santiago Mattiauda")
                url.set(findProperty("POM_DEVELOPER_URL") as String? ?: "https://github.com/santimattius/")
            }
        }
        scm {
            url.set(findProperty("POM_SCM_URL") as String? ?: "https://github.com/santimattius/android-hilt-extension/")
            connection.set("scm:git:git://github.com/santimattius/android-hilt-extension.git")
            developerConnection.set("scm:git:ssh://git@github.com/santimattius/android-hilt-extension.git")
        }
    }
}
