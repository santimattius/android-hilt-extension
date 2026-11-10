package com.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TestKit builds of a throwaway Android app. AGP creates `transform<Variant>ClassesWithAsm` only
 * for variants that registered a class visitor factory, so a dry run of that task shows whether
 * the plugin registered the variant.
 */
class PluginWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `default variants register debug and benchmark`() {
        val result = run("", ":transformDebugClassesWithAsm", ":transformBenchmarkClassesWithAsm")
        assertTrue(result, ":transformDebugClassesWithAsm SKIPPED" in result && ":transformBenchmarkClassesWithAsm SKIPPED" in result)
    }

    @Test
    fun `release is not registered by default`() {
        val result = run("", ":transformReleaseClassesWithAsm", fails = true)
        assertTrue(result, "transformReleaseClassesWithAsm' not found" in result)
    }

    @Test
    fun `variants list selects the registered variants`() {
        val script = "hiltTrace { variants.set(listOf(\"release\")) }"
        assertTrue(run(script, ":transformReleaseClassesWithAsm").contains(":transformReleaseClassesWithAsm SKIPPED"))
        assertTrue("transformDebugClassesWithAsm' not found" in run(script, ":transformDebugClassesWithAsm", fails = true))
    }

    @Test
    fun `enabled false registers nothing`() {
        val result = run("hiltTrace { enabled.set(false) }", ":transformDebugClassesWithAsm", fails = true)
        assertTrue(result, "transformDebugClassesWithAsm' not found" in result)
    }

    @Test
    fun `second run reuses the configuration cache`() {
        run("", ":transformDebugClassesWithAsm", "--configuration-cache")
        val second = run("", ":transformDebugClassesWithAsm", "--configuration-cache")
        assertTrue(second, "Reusing configuration cache" in second)
    }

    @Test
    fun `fastInit disabled logs one warning for all instrumented variants and the build succeeds`() {
        val result = run("", ":transformDebugClassesWithAsm", ":transformBenchmarkClassesWithAsm", "-Pdagger.hilt.fastInit=false")
        assertEquals(result, 1, Regex("Hilt Trace: .*fastInit").findAll(result).count())
        assertTrue(result, ":transformDebugClassesWithAsm SKIPPED" in result && ":transformBenchmarkClassesWithAsm SKIPPED" in result)
    }

    @Test
    fun `fastInit left at its default logs no warning`() {
        val result = run("", ":transformDebugClassesWithAsm", ":transformBenchmarkClassesWithAsm")
        assertEquals(result, 0, Regex("Hilt Trace: .*fastInit").findAll(result).count())
    }

    private fun run(extension: String, vararg args: String, fails: Boolean = false): String {
        projectDir.root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        System.getenv("ANDROID_HOME")?.let { projectDir.root.resolve("local.properties").writeText("sdk.dir=$it") }
        projectDir.root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.santimattius.android.hilt.trace")
            }
            android {
                namespace = "fixture.app"
                compileSdk = 36
                defaultConfig { minSdk = 24 }
                buildTypes { create("benchmark") { initWith(getByName("release")) } }
            }
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "com.santimattius.android") }
            $extension
            """.trimIndent(),
        )
        val runner = GradleRunner.create().withProjectDir(projectDir.root).withPluginClasspath()
            .withArguments(*args, "--dry-run", "--stacktrace")
        return (if (fails) runner.buildAndFail() else runner.build()).output
    }
}
