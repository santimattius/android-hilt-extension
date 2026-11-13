package com.santimattius.android.hilt.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Shared Android library setup: SDK levels come from the root `gradle.properties`; the Kotlin
 * language level follows [KotlinCompatibility].
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")
        target.configureKotlinCompatibility()
        target.extensions.configure<LibraryExtension> {
            compileSdk = target.intProperty("androidTargetSdkVersion")
            defaultConfig {
                minSdk = target.intProperty("androidMinSdkVersion")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            buildTypes {
                release {
                    isMinifyEnabled = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                    )
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}

internal fun Project.intProperty(name: String): Int =
    (property(name) as String).toInt()
