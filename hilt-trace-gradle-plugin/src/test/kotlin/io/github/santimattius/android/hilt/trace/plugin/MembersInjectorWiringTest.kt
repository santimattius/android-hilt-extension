package io.github.santimattius.android.hilt.trace.plugin

import io.github.santimattius.android.hilt.trace.plugin.asm.readClass
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.tree.MethodInsnNode

/**
 * TestKit builds of a throwaway Android app with one class named like a generated members injector:
 * `hiltTrace { traceMembersInjectors }` is off by default (the class is not even offered to the
 * transform) and wraps `injectMembers(T)` when set.
 */
class MembersInjectorWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `by default a members injector is not instrumented`() {
        assertEquals(emptyList<String>(), build(extension = ""))
    }

    @Test
    fun `explicitly off is the same as the default`() {
        assertEquals(emptyList<String>(), build(extension = "hiltTrace { traceMembersInjectors.set(false) }"))
    }

    @Test
    fun `opting in wraps injectMembers and only that method`() {
        assertEquals(listOf("begin", "end", "end"), build(extension = "hiltTrace { traceMembersInjectors.set(true) }"))
    }

    /** The `HiltTrace` calls `injectMembers` of the transformed debug class has after the build. */
    private fun build(extension: String): List<String> {
        val root = projectDir.root
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("io.github.santimattius.android.hilt.trace")
            }
            android { namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "io.github.santimattius.android") }
            $extension
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("src/main/java/fixture/Thing.java").apply { parentFile.mkdirs() }.writeText("package fixture; public final class Thing { public void touch() {} }")
        root.resolve("src/main/java/fixture/Thing_MembersInjector.java").writeText(
            "package fixture; public final class Thing_MembersInjector { public void injectMembers(Thing thing) { thing.touch(); } }",
        )
        GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(":transformDebugClassesWithAsm", "--stacktrace").build()
        val file = root.resolve("build").walkTopDown().first { it.name == "Thing_MembersInjector.class" && "transformDebugClassesWithAsm" in it.path }
        return readClass(file.readBytes()).methods.single { it.name == "injectMembers" }.instructions.filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "io/github/santimattius/android/hilt/trace/HiltTrace" }.map { it.name }
    }
}
