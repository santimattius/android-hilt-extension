pluginManagement {
    val sampleJitPackVersion = providers.gradleProperty("sampleJitPackVersion").orNull
    includeBuild("build-logic")
    if (sampleJitPackVersion == null) {
        includeBuild("hilt-trace-gradle-plugin")
    } else {
        resolutionStrategy.eachPlugin {
            if (requested.id.id == "io.github.santimattius.android.hilt.trace") {
                useModule("com.github.santimattius.android-hilt-extension:hilt-trace-gradle-plugin:$sampleJitPackVersion")
            }
        }
    }
    repositories {
        if (sampleJitPackVersion != null) {
            maven("https://jitpack.io") {
                content { includeGroup("com.github.santimattius.android-hilt-extension") }
            }
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
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("sampleJitPackVersion").isPresent) {
            maven("https://jitpack.io") {
                content { includeGroup("com.github.santimattius.android-hilt-extension") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "android-hilt-extension"
include(":app")
include(":sample-feature")
include(":hilt-trace-runtime")
include(":hilt-coroutines-runtime")
include(":hilt-trace-compiler")
include(":benchmark-micro")
