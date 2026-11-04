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
    namespace = "com.santimattius.android.library"
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
        description.set(findProperty("POM_DESCRIPTION") as String? ?: "Android library template")
        inceptionYear.set(findProperty("POM_INCEPTION_YEAR") as String? ?: "2025")
        url.set(findProperty("POM_URL") as String? ?: "https://github.com/your-org/android-library-template/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(findProperty("POM_DEVELOPER_ID") as String? ?: "your-username")
                name.set(findProperty("POM_DEVELOPER_NAME") as String? ?: "Your Name")
                url.set(findProperty("POM_DEVELOPER_URL") as String? ?: "https://github.com/your-username/")
            }
        }
        scm {
            url.set(findProperty("POM_SCM_URL") as String? ?: "https://github.com/your-org/android-library-template/")
            connection.set("scm:git:git://github.com/your-org/android-library-template.git")
            developerConnection.set("scm:git:ssh://git@github.com/your-org/android-library-template.git")
        }
    }
}
