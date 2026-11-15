package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A library with its own product flavor dimension (`tier`: `free`, `paid`) that the app does not have, or
 * has with a flavor name the library lacks.
 *
 * Fixture: each library flavor provides a different service (`FreeService`, `PaidService`), so the catalogue
 * that was read is visible in the app dex: a slice named from the catalogue is `fixture.lib.<Service>`,
 * the bytecode fallback is `fixture.lib.<Service>Module_ProvideFactory`.
 *
 * What it proves: with `missingDimensionStrategy("tier", "free")` the app `debug` variant selects the
 * library's `freeDebug`, and with a `premium` flavor in the same dimension whose `matchingFallbacks` is `paid` the app
 * `premiumDebug` variant selects `paidDebug`. Only the selected variant's real slice names are in the dex,
 * and nothing is warned.
 */
class LibraryFlavorDimensionTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `missingDimensionStrategy reads the catalogue of the library variant it selects`() {
        val output = build(appFlavor = false)

        val dex = dex("debug")
        assertTrue(output, "fixture.lib.FreeService" in dex && "fixture.lib.FreeServiceModule_ProvideFactory" !in dex)
        assertTrue(output, "PaidService" !in dex)
        assertTrue(output, "Hilt Trace" !in output)
    }

    @Test
    fun `matchingFallbacks reads the catalogue of the library variant it selects`() {
        val output = build(appFlavor = true)

        val dex = dex("premiumDebug")
        assertTrue(output, "fixture.lib.PaidService" in dex && "fixture.lib.PaidServiceModule_ProvideFactory" !in dex)
        assertTrue(output, "FreeService" !in dex)
        assertTrue(output, "Hilt Trace" !in output)
    }

    private fun dex(variant: String): String = projectDir.root.resolve("build/intermediates/dex/$variant").walkTopDown()
        .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun build(appFlavor: Boolean): String {
        val root = projectDir.root
        val compilerJar = checkNotNull(System.getProperty("hiltTrace.compilerJar")) { "hiltTrace.compilerJar is not set" }.replace("\\", "/")
        root.resolve("settings.gradle.kts").writeText(
            "dependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = \"fixture\"\ninclude(\":lib\")",
        )
        root.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        val flavors = if (appFlavor) {
            "flavorDimensions += \"tier\"; productFlavors { create(\"premium\") { dimension = \"tier\"; matchingFallbacks += \"paid\" } }"
        } else {
            "defaultConfig.missingDimensionStrategy(\"tier\", \"free\")"
        }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.google.devtools.ksp")
                id("com.google.dagger.hilt.android")
                id("io.github.santimattius.android.hilt.trace")
            }
            android {
                namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                lint { checkReleaseBuilds = false }
                $flavors
            }
            hiltTrace { variants.set(listOf("debug", "premiumDebug")) }
            ksp { arg("hiltTrace.module", ":app") }
            dependencies {
                implementation(project(":lib"))
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            configurations.all { exclude(group = "io.github.santimattius.android") }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:name=\".App\" /></manifest>",
        )
        root.resolve("src/main/java/io/github/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package io.github.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        root.resolve("src/main/kotlin/fixture/app/App.kt").apply { parentFile.mkdirs() }.writeText(
            "package fixture.app\n\nimport android.app.Application\nimport dagger.hilt.android.HiltAndroidApp\n\n@HiltAndroidApp\nclass App : Application()\n",
        )
        root.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins { id("com.android.library"); id("com.google.devtools.ksp") }
            android {
                namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                flavorDimensions += "tier"
                productFlavors { create("free") { dimension = "tier" }; create("paid") { dimension = "tier" } }
            }
            ksp { arg("hiltTrace.module", ":lib") }
            dependencies {
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            """.trimIndent(),
        )
        root.resolve("lib/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        service("free", "FreeService")
        service("paid", "PaidService")
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(if (appFlavor) ":assemblePremiumDebug" else ":assembleDebug", "--stacktrace").build().output
    }

    /** The library flavor source set [flavor] provides [service] through a module and an entry point. */
    private fun service(flavor: String, service: String) {
        projectDir.root.resolve("lib/src/$flavor/kotlin/fixture/lib/$service.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package fixture.lib

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.EntryPoint
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import javax.inject.Singleton

            class $service

            @Module
            @InstallIn(SingletonComponent::class)
            object ${service}Module {
                @Provides
                @Singleton
                fun provide(): $service = $service()
            }

            @EntryPoint
            @InstallIn(SingletonComponent::class)
            interface ${service}EntryPoint {
                fun get$service(): $service
            }
            """.trimIndent(),
        )
    }

    private companion object {
        const val HILT = "2.60.1"
    }
}
