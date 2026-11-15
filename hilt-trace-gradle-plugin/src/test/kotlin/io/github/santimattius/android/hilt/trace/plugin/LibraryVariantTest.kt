package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An Android library project is read from `generated/ksp/<library variant>/resources`, where the library
 * variant is the one Gradle selected for the app variant.
 *
 * What it proves: when the app has a product flavor the library lacks (`freeDebug` against the
 * library's `debug`), the selected `debug` is read and nothing is logged (the catalogue content is
 * asserted in `RealHiltLibraryTest`). A library with KSP-named tasks but none for the selected variant is
 * skipped with one warning naming the library and the variant, and the build passes. A library without
 * KSP logs nothing.
 *
 * What it does not prove: the missing-variant case uses a hand-registered KSP-named task, because AGP with
 * the KSP plugin creates a KSP task for every variant, so it is a defensive path.
 */
class LibraryVariantTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `a library without the app-only flavor is read through the variant Gradle selected and logs no warning`() {
        val output = build(appFlavors = true, libraryKsp = true)

        assertTrue(output, "no KSP variant" !in output)
        assertTrue(output, "resolved during configuration time" !in output)
    }

    @Test
    fun `a library with KSP tasks but none for the selected variant is skipped with one warning and the build passes`() {
        val output = build(appFlavors = true, libraryKsp = false, kspLookalike = true)

        val warnings = output.lines().filter { it.startsWith("Hilt Trace:") && "no KSP variant" in it }
        assertEquals(output, 1, warnings.size)
        assertTrue(output, ":lib" in warnings.single() && "'debug'" in warnings.single())
    }

    @Test
    fun `a library that has the app variant logs no variant warning`() {
        val output = build(appFlavors = false, libraryKsp = true)

        assertTrue(output, "no KSP variant" !in output)
    }

    @Test
    fun `a library without KSP logs no variant warning`() {
        val output = build(appFlavors = true, libraryKsp = false)

        assertTrue(output, "no KSP variant" !in output)
    }

    private fun build(appFlavors: Boolean, libraryKsp: Boolean, kspLookalike: Boolean = false): String {
        val root = projectDir.root
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            include(":lib")
            """.trimIndent(),
        )
        root.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        val flavors = if (appFlavors) {
            """
            flavorDimensions += "tier"
            productFlavors { create("free") { dimension = "tier" } }
            defaultConfig.missingDimensionStrategy("tier", "free")
            """.trimIndent()
        } else {
            ""
        }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("io.github.santimattius.android.hilt.trace")
            }
            android {
                namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 }
                lint { checkReleaseBuilds = false }
                $flavors
            }
            hiltTrace { variants.set(listOf("debug", "freeDebug")) }
            dependencies { implementation(project(":lib")) }
            configurations.all { exclude(group = "io.github.santimattius.android") }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("src/main/java/io/github/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package io.github.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        // A KSP-named task that is not for the selected variant: AGP with KSP creates one per variant.
        val lookalike = if (kspLookalike) """tasks.register("kspLegacyKotlin")""" else ""
        val ksp = if (libraryKsp) """id("com.google.devtools.ksp")""" else ""
        root.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                id("com.android.library")
                $ksp
            }
            android { namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            $lookalike
            """.trimIndent(),
        )
        root.resolve("lib/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("lib/src/main/kotlin/fixture/lib/Lib.kt").apply { parentFile.mkdirs() }.writeText("package fixture.lib\n\nclass Lib\n")
        val task = if (appFlavors) ":assembleFreeDebug" else ":assembleDebug"
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(task, "--stacktrace").build().output
    }
}
