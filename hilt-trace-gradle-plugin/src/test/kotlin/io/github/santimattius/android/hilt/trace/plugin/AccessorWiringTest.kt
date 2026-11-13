package io.github.santimattius.android.hilt.trace.plugin

import io.github.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import io.github.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import io.github.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import io.github.santimattius.android.hilt.trace.plugin.asm.readClass
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * TestKit builds of a throwaway Android app that has two classes named like Hilt component
 * implementations, so the real factory, run by AGP, decides what happens to each of them. The
 * catalogue sits where the app's KSP task would write it.
 */
class AccessorWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val straight = EntryPoint("fixture.Exposed", "consumer", "()Ljava/lang/String;", "dagger.hilt.components.SingletonComponent")
    private val branching = EntryPoint("fixture.Exposed", "branchy", "(Z)Ljava/lang/String;", "dagger.hilt.components.SingletonComponent")

    @Test
    fun `the factory wraps a catalogued accessor and replays a class it cannot transform`() {
        val output = build(catalogue = listOf(straight, branching))

        val singleton = transformed("DaggerApp_HiltComponents_SingletonC\$SingletonCImpl")
        // One begin, then an end on the normal path and one in the catch-all handler.
        assertEquals(output, listOf("begin", "end", "end"), facadeCalls(singleton, "consumer"))
        assertEquals(output, emptyList<String>(), facadeCalls(singleton, "other"))
        // Rule J has no injection target here, so the inject method is not a match.
        assertEquals(output, emptyList<String>(), facadeCalls(singleton, "injectThing"))
        // The catalogued accessor of the second class has a branch: the class is replayed exactly as read.
        val activity = transformed("DaggerApp_HiltComponents_ActivityC\$ActivityCImpl")
        assertTrue(activity.methods.any { it.name == "branchy" })
        assertEquals(output, emptyList<String>(), facadeCalls(activity, "branchy"))
        assertEquals(output, 0, Regex("no catalogue found").findAll(output).count())
    }

    @Test
    fun `without a catalogue the build warns once and the component classes are left alone`() {
        val output = build(catalogue = null)

        val singleton = transformed("DaggerApp_HiltComponents_SingletonC\$SingletonCImpl")
        assertEquals(output, emptyList<String>(), facadeCalls(singleton, "consumer") + facadeCalls(singleton, "injectThing"))
        assertEquals(output, 1, Regex("no catalogue found").findAll(output).count())
    }

    @Test
    fun `an injection target alone makes the factory wrap the inject method and nothing else`() {
        val output = build(catalogue = emptyList(), targets = listOf(InjectionTarget("fixture.Thing")))

        val singleton = transformed("DaggerApp_HiltComponents_SingletonC\$SingletonCImpl")
        assertEquals(output, listOf("begin", "end", "end"), facadeCalls(singleton, "injectThing"))
        assertEquals(output, emptyList<String>(), facadeCalls(singleton, "consumer") + facadeCalls(singleton, "other"))
        assertEquals(output, 0, Regex("no catalogue found").findAll(output).count())
    }

    private fun facadeCalls(node: ClassNode, method: String): List<String> =
        node.methods.single { it.name == method }.instructions.filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "io/github/santimattius/android/hilt/trace/HiltTrace" }.map { it.name }

    private fun transformed(className: String): ClassNode {
        val file = projectDir.root.resolve("build").walkTopDown()
            .firstOrNull { it.name == "$className.class" && "transformDebugClassesWithAsm" in it.path }
        return readClass(checkNotNull(file) { "no transformed $className" }.readBytes())
    }

    private fun build(catalogue: List<EntryPoint>?, targets: List<InjectionTarget> = emptyList()): String {
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
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "io.github.santimattius.android") }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        source("DaggerApp_HiltComponents_SingletonC", "SingletonCImpl", "public String consumer() { return \"c\"; } public String other() { return \"o\"; } public void injectThing(Thing thing) { thing.touch(); }")
        source("DaggerApp_HiltComponents_ActivityC", "ActivityCImpl", "public String branchy(boolean b) { return b ? \"y\" : \"n\"; }")
        root.resolve("src/main/java/fixture/Thing.java").writeText("package fixture; public final class Thing { public void touch() {} }")
        catalogue?.let {
            val json = JsonWriter.write(Catalogue(":app", emptyList(), it, targets))
            root.resolve("build/generated/ksp/debug/resources/META-INF/hilt-trace/app.json").apply { parentFile.mkdirs() }.writeText(json)
        }
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(":transformDebugClassesWithAsm", "--stacktrace").build().output
    }

    private fun source(outer: String, inner: String, members: String) {
        projectDir.root.resolve("src/main/java/fixture/$outer.java").apply { parentFile.mkdirs() }
            .writeText("package fixture; public final class $outer { public static final class $inner { $members } }")
    }
}
