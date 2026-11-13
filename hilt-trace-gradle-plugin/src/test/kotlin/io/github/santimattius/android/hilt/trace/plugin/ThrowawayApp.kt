package io.github.santimattius.android.hilt.trace.plugin

import io.github.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import io.github.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import io.github.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import io.github.santimattius.android.hilt.trace.plugin.asm.readClass
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import org.gradle.testkit.runner.GradleRunner
import org.objectweb.asm.tree.MethodInsnNode

/**
 * A throwaway Android application for TestKit builds that look at what a release build packages.
 * It has one class named like a Hilt component implementation with a catalogued accessor, and the
 * catalogue arrives in both forms an APK can carry it: inside a library jar and as an app resource.
 * [withPlugin] false gives the plugin-free twin; the root project name is fixed so the Kotlin
 * module name, which leaks into class bytes, is equal in both.
 */
internal class ThrowawayApp(private val root: File, private val withPlugin: Boolean, private val buildScriptExtras: String = "") {

    private val accessor = EntryPoint("fixture.Exposed", "consumer", "()Ljava/lang/String;", "dagger.hilt.components.SingletonComponent")
    private val catalogue = JsonWriter.write(Catalogue(":lib", emptyList(), listOf(accessor), emptyList()))

    /** Writes the project and runs [tasks]; returns the build output. */
    fun build(vararg tasks: String): String {
        write()
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(tasks.toList() + "--stacktrace").build().output
    }

    /** The entry names of the single APK AGP wrote for [variant]. */
    fun apkEntries(variant: String): List<String> = ZipFile(apk(variant)).use { zip -> zip.entries().toList().map { it.name } }

    private fun apk(variant: String): File = root.resolve("build/outputs/apk/$variant").listFiles { f -> f.extension == "apk" }.orEmpty().single()

    /** The catalogue entries of the APK of [variant], sorted. */
    fun catalogueEntries(variant: String): List<String> = apkEntries(variant).filter { it.startsWith("META-INF/hilt-trace/") }.sorted()

    /** The `HiltTrace` method names [method] calls in the class AGP's ASM transform wrote for debug. */
    fun debugFacadeCalls(method: String): List<String> {
        val file = root.resolve("build").walkTopDown().firstOrNull { it.name == "$COMPONENT.class" && "transformDebugClassesWithAsm" in it.path }
        return readClass(checkNotNull(file) { "no transformed $COMPONENT" }.readBytes()).methods.single { it.name == method }
            .instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == "io/github/santimattius/android/hilt/trace/HiltTrace" }.map { it.name }
    }

    /** Relative path to SHA-256 of every class file and dex entry the release build produced. */
    fun releaseCode(): Map<String, String> {
        val classes = root.resolve("build/intermediates").walkTopDown()
            .filter { it.isFile && it.extension == "class" && "/release/" in it.path }
            .associate { it.relativeTo(root).path to sha256(it.readBytes()) }
        val dex = ZipFile(apk("release")).use { zip ->
            zip.entries().toList().filter { it.name.endsWith(".dex") }.associate { "apk/${it.name}" to sha256(zip.getInputStream(it).readBytes()) }
        }
        return classes + dex
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write() {
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        val plugin = if (withPlugin) """id("io.github.santimattius.android.hilt.trace")""" else ""
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                $plugin
            }
            android {
                namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 }
                lint { checkReleaseBuilds = false }
            }
            dependencies { implementation(files("libs/lib.jar")) }
            // The plugin-added coordinates are published later; they are irrelevant to these builds.
            configurations.all { exclude(group = "io.github.santimattius.android") }
            $buildScriptExtras
            tasks.register("printTransformTasks") {
                doLast { project.tasks.names.filter { it.startsWith("transform") && it.endsWith("ClassesWithAsm") }.forEach { println("TASK " + it) } }
            }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("src/main/resources/META-INF/hilt-trace/app.json").apply { parentFile.mkdirs() }.writeText(catalogue)
        root.resolve("src/main/resources/fixture/keep.txt").apply { parentFile.mkdirs() }.writeText("kept")
        root.resolve("src/main/java/fixture/$OUTER.java").apply { parentFile.mkdirs() }.writeText(
            "package fixture; public final class $OUTER { public static final class $INNER { public String consumer() { return \"c\"; } } }",
        )
        root.resolve("src/main/java/io/github/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package io.github.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        val jar = root.resolve("libs/lib.jar").apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/hilt-trace/lib.json"))
            out.write(catalogue.toByteArray())
        }
    }

    private companion object {
        const val OUTER = "DaggerApp_HiltComponents_SingletonC"
        const val INNER = "SingletonCImpl"
        const val COMPONENT = "$OUTER\$$INNER"
    }
}
