package com.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec scenario "Library injector coverage": a class that lives in a library module of the app is
 * instrumented by the app's debug transform, which needs `InstrumentationScope.ALL`.
 *
 * What it proves: in a real AGP build of a throwaway app with an Android library subproject
 * (`:lib`), a class compiled in `:lib` and shaped like a generated provider (`LibGraph$SwitchingProvider`,
 * a `get()` that switches on `id` and constructs `LibGraph$Thing`) is accepted by the name filter
 * and ends up in the debug dex carrying the `hilt.create` category and the `fixture.lib.LibGraph$Thing`
 * slice name, strings that only the create rule can add (the sources hold the class as
 * `fixture/lib/LibGraph$Thing`). The release dex holds the same provider class without either, and the
 * app has no release transform task. The facade descriptor is not used as a marker because the
 * app's own `HiltTrace` class puts it in every dex. AGP applies `ALL` scope to dependencies through artifact transforms that leave only dex, so the
 * dex strings are the observable output; the `begin`/`end` call sites themselves are covered by the
 * ASM level tests, not read here.
 *
 * What it does not prove: the provider is handwritten Java with the generated shape, not produced by
 * Dagger/Hilt from a real library module, so it does not show that Hilt emits such a class in a
 * library; the dex checks are string-level, not a decoded call graph.
 */
class LibraryInstrumentationTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val libraryClass = "fixture.lib.LibGraph\$SwitchingProvider"
    private val providerDescriptor = "Lfixture/lib/LibGraph\$SwitchingProvider;"
    private val instrumentationMarkers = listOf("hilt.create", "fixture.lib.LibGraph\$Thing")

    @Test
    fun `a switching provider that lives in a library module is instrumented in debug only`() {
        val output = build()

        assertTrue(output, isInstrumentableClassName(libraryClass))
        val debug = dex("debug")
        val release = dex("release")
        // Both dex sets hold the library provider, so the marker comparison has content.
        assertTrue(output, providerDescriptor in debug)
        assertTrue(output, providerDescriptor in release)
        assertEquals(output, instrumentationMarkers, instrumentationMarkers.filter { it in debug })
        assertEquals(output, emptyList<String>(), instrumentationMarkers.filter { it in release })
        val transformTasks = output.lines().filter { it.startsWith("TASK ") }
        assertTrue(output, "TASK transformDebugClassesWithAsm" in transformTasks)
        assertFalse(output, transformTasks.any { it.contains("Release") })
    }

    /** The bytes of every dex file the app build of [variant] wrote, as one Latin-1 string so ASCII strings can be searched. */
    private fun dex(variant: String): String = projectDir.root.resolve("build/intermediates/dex/$variant").walkTopDown()
        .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun build(): String {
        val root = projectDir.root
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            include(":lib")
            """.trimIndent(),
        )
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.santimattius.android.hilt.trace")
            }
            android {
                namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 }
                lint { checkReleaseBuilds = false }
            }
            dependencies { implementation(project(":lib")) }
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "com.santimattius.android") }
            tasks.register("printTransformTasks") {
                doLast { project.tasks.names.filter { it.startsWith("transform") && it.endsWith("ClassesWithAsm") }.forEach { println("TASK " + it) } }
            }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("src/main/java/com/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package com.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        root.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins { id("com.android.library") }
            android { namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            """.trimIndent(),
        )
        root.resolve("lib/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("lib/src/main/java/fixture/lib/LibGraph.java").apply { parentFile.mkdirs() }.writeText(
            "package fixture.lib; public final class LibGraph { public static final class Thing {} " +
                "public static final class SwitchingProvider { private final int id; public SwitchingProvider(int id) { this.id = id; } " +
                "public Object get() { switch (id) { case 0: return new Thing(); default: throw new AssertionError(id); } } } }",
        )
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(":assembleDebug", ":assembleRelease", "printTransformTasks", "--stacktrace").build().output
    }
}
