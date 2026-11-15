package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An Android library project is read from `generated/ksp/<app variant>/resources`, so the library must
 * have a KSP variant with the app variant's name.
 *
 * What it proves: when the app has a product flavor the library lacks (`freeDebug` against the
 * library's `debug`), the build still passes (fail open) and the plugin logs one warning that names
 * the library and the variant. A library that has the variant, or no KSP at all, logs nothing.
 *
 * What it does not prove: that the catalogue is found for such a library; it is not (documented limit).
 */
class LibraryVariantTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `a library without the app variant is skipped with one warning and the build passes`() {
        val output = build(appFlavors = true, libraryKsp = true)

        val warnings = output.lines().filter { it.startsWith("Hilt Trace:") && "no KSP variant" in it }
        assertEquals(output, 1, warnings.size)
        assertTrue(output, ":lib" in warnings.single() && "freeDebug" in warnings.single())
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

    private fun build(appFlavors: Boolean, libraryKsp: Boolean): String {
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
        val ksp = if (libraryKsp) """id("com.google.devtools.ksp")""" else ""
        root.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                id("com.android.library")
                $ksp
            }
            android { namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            """.trimIndent(),
        )
        root.resolve("lib/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("lib/src/main/kotlin/fixture/lib/Lib.kt").apply { parentFile.mkdirs() }.writeText("package fixture.lib\n\nclass Lib\n")
        val task = if (appFlavors) ":assembleFreeDebug" else ":assembleDebug"
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(task, "--stacktrace").build().output
    }
}
