package com.santimattius.android.hilt.buildlogic

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * The oldest Kotlin toolchain consumers may use, kept in one place.
 *
 * The published artifacts carry Kotlin metadata for [LANGUAGE_VERSION] and their POMs depend on
 * `kotlin-stdlib` [CORE_LIBRARIES_VERSION]. Without this, artifacts built with a newer compiler
 * (the one this repository builds with) are rejected by older consumer compilers ("binary version
 * of its metadata is X, expected Y") and pull a newer `kotlin-stdlib` into the consumer.
 */
internal object KotlinCompatibility {
    val LANGUAGE_VERSION: KotlinVersion = KotlinVersion.KOTLIN_2_2

    /** Matches [LANGUAGE_VERSION]; the stdlib version written into the published POM. */
    const val CORE_LIBRARIES_VERSION = "2.2.21"
}

/**
 * Applies [KotlinCompatibility] to the Kotlin extension of this project. The extension may only
 * appear once the Android plugin has finished configuring (AGP's built-in Kotlin), so the lookup
 * falls back to `afterEvaluate`.
 */
internal fun Project.configureKotlinCompatibility() {
    if (!applyKotlinCompatibility()) {
        afterEvaluate { applyKotlinCompatibility() }
    }
}

private fun Project.applyKotlinCompatibility(): Boolean {
    val kotlin = extensions.findByName("kotlin") as? KotlinBaseExtension ?: return false
    (kotlin as? HasConfigurableKotlinCompilerOptions<*>)?.compilerOptions?.apply {
        languageVersion.set(KotlinCompatibility.LANGUAGE_VERSION)
        apiVersion.set(KotlinCompatibility.LANGUAGE_VERSION)
    }
    kotlin.coreLibrariesVersion = KotlinCompatibility.CORE_LIBRARIES_VERSION
    return true
}
