// Throwaway consumer used by the compatibility CI job. It is NOT part of the main build: it is
// driven by compat/run.sh against artifacts published to a local Maven repository, and every
// toolchain version comes from a -Pcompat* property so a matrix can vary them.
pluginManagement {
    val agp = providers.gradleProperty("compatAgp").get()
    val kotlin = providers.gradleProperty("compatKotlin").get()
    val ksp = providers.gradleProperty("compatKsp").get()
    val hilt = providers.gradleProperty("compatHilt").get()
    val library = providers.gradleProperty("compatLibraryVersion").get()
    repositories {
        // Only this repository's own plugin comes from the local repository.
        mavenLocal {
            content { includeGroupByRegex("com\\.santimattius.*") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version agp
        id("org.jetbrains.kotlin.plugin.compose") version kotlin
        id("com.google.devtools.ksp") version ksp
        id("com.google.dagger.hilt.android") version hilt
        id("com.santimattius.android.hilt.trace") version library
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal {
            content { includeGroupByRegex("com\\.santimattius.*") }
        }
    }
}

rootProject.name = "consumer"
