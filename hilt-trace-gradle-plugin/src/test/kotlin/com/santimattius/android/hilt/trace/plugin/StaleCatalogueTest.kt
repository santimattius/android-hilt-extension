package com.santimattius.android.hilt.trace.plugin

import com.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import com.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import com.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import com.santimattius.android.hilt.trace.plugin.asm.readClass
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.tree.LdcInsnNode

/**
 * Two builds in one project directory, run by the same daemon, where only the catalogue inside a
 * library jar changes between them. The slice name that rule A writes into the component class
 * must follow the catalogue of the latest build, with the configuration cache on or off.
 */
class StaleCatalogueTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `a changed library catalogue renames the slice with the configuration cache on`() {
        val first = build(component = "dagger.hilt.components.SingletonComponent", "--configuration-cache")
        assertEquals(listOf("SingletonComponent.consumer"), sliceNames())
        assertEquals(TaskOutcome.SUCCESS, first.task(TRANSFORM)?.outcome)

        val second = build(component = "dagger.hilt.android.components.ActivityComponent", "--configuration-cache")

        assertTrue(second.output, "Reusing configuration cache." in second.output)
        assertEquals(second.output, listOf("ActivityComponent.consumer"), sliceNames())
        assertEquals(second.output, TaskOutcome.SUCCESS, second.task(TRANSFORM)?.outcome)
    }

    @Test
    fun `a changed library catalogue renames the slice without the configuration cache`() {
        build(component = "dagger.hilt.components.SingletonComponent", "--no-configuration-cache")
        assertEquals(listOf("SingletonComponent.consumer"), sliceNames())

        val second = build(component = "dagger.hilt.android.components.ActivityComponent", "--no-configuration-cache")

        assertEquals(second.output, listOf("ActivityComponent.consumer"), sliceNames())
        assertEquals(second.output, TaskOutcome.SUCCESS, second.task(TRANSFORM)?.outcome)
    }

    @Test
    fun `an unchanged library catalogue leaves the transform up to date`() {
        build(component = "dagger.hilt.components.SingletonComponent", "--configuration-cache")

        val second = build(component = "dagger.hilt.components.SingletonComponent", "--configuration-cache")

        assertEquals(second.output, TaskOutcome.UP_TO_DATE, second.task(TRANSFORM)?.outcome)
        assertEquals(listOf("SingletonComponent.consumer"), sliceNames())
    }

    private fun sliceNames(): List<String> {
        val file = projectDir.root.resolve("build").walkTopDown()
            .firstOrNull { it.name == "$COMPONENT.class" && "transformDebugClassesWithAsm" in it.path }
        return readClass(checkNotNull(file) { "no transformed $COMPONENT" }.readBytes()).methods.single { it.name == "consumer" }
            .instructions.filterIsInstance<LdcInsnNode>().map { it.cst }.filterIsInstance<String>().filter { it.endsWith(".consumer") }
    }

    private fun build(component: String, vararg flags: String) = writeProject(component).let {
        GradleRunner.create().withProjectDir(projectDir.root).withPluginClasspath()
            .withArguments(listOf(TRANSFORM, "--stacktrace") + flags).build()
    }

    private fun writeProject(component: String) {
        val root: File = projectDir.root
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
            dependencies { implementation(files("libs/lib.jar")) }
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "io.github.santimattius.android") }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("src/main/java/fixture/DaggerApp_HiltComponents_SingletonC.java").apply { parentFile.mkdirs() }.writeText(
            "package fixture; public final class DaggerApp_HiltComponents_SingletonC { " +
                "public static final class SingletonCImpl { public String consumer() { return \"c\"; } } }",
        )
        root.resolve("src/main/java/com/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package com.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        val entry = EntryPoint("fixture.Exposed", "consumer", "()Ljava/lang/String;", component)
        val json = JsonWriter.write(Catalogue(":lib", emptyList(), listOf(entry), emptyList()))
        JarOutputStream(root.resolve("libs/lib.jar").apply { parentFile.mkdirs() }.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/hilt-trace/lib.json"))
            out.write(json.toByteArray())
        }
    }

    private companion object {
        const val TRANSFORM = ":transformDebugClassesWithAsm"
        const val COMPONENT = "DaggerApp_HiltComponents_SingletonC\$SingletonCImpl"
    }
}
