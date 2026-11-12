pluginManagement {
    includeBuild("build-logic")
    includeBuild("hilt-trace-gradle-plugin")
    repositories {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "android-hilt-extension"
include(":app")
include(":hilt-trace-runtime")
include(":hilt-coroutines-runtime")
include(":hilt-trace-compiler")
