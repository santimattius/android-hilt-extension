package com.santimattius.android.hilt.trace.plugin

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TestKit builds that execute the artifact transform on real dependency forms: a jar, a classes
 * directory and a project dependency (an Android library). The build script resolves the artifact
 * type the plugin registers and prints the catalogue files it finds.
 */
class CatalogueTransformWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val printScript = """
        tasks.register("printCatalogues") {
            val view = configurations["debugRuntimeClasspath"].incoming.artifactView {
                attributes.attribute(Attribute.of("artifactType", String::class.java), "hilt-trace-catalogue")
            }.files
            inputs.files(view) // wires the build of project dependencies before the files are read
            doLast { view.files.forEach { root -> root.walkTopDown().filter { it.isFile }.forEach { println("CATALOGUE " + it.name) } } }
        }
        // The plugin-added coordinates are published later; they are irrelevant to these resolutions.
        configurations.all { exclude(group = "com.santimattius.android") }
    """.trimIndent()

    @Test
    fun `jar and directory forms are both read`() {
        writeJar("libs/lib.jar", "_jar.json")
        projectDir.root.resolve("classes/META-INF/hilt-trace").mkdirs()
        projectDir.root.resolve("classes/META-INF/hilt-trace/_dir.json").writeText("{}")

        val output = build(appScript = """dependencies { "debugRuntimeOnly"(files("libs/lib.jar", "classes")) }""")

        assertEquals(output, listOf("CATALOGUE _dir.json", "CATALOGUE _jar.json"), lines(output, "CATALOGUE "))
    }

    @Test
    fun `a project dependency catalogue is read`() {
        // AGP ignores java resources whose file name starts with an underscore, so the fixture avoids it.
        projectDir.root.resolve("lib/src/main/resources/META-INF/hilt-trace").mkdirs()
        projectDir.root.resolve("lib/src/main/resources/META-INF/hilt-trace/lib.json").writeText("{}")
        projectDir.root.resolve("lib/src/main/AndroidManifest.xml").writeText("<manifest />")
        projectDir.root.resolve("lib/build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") }
            android { namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            """.trimIndent(),
        )

        val output = build(appScript = """dependencies { implementation(project(":lib")) }""", include = ":lib")

        assertEquals(output, listOf("CATALOGUE lib.json"), lines(output, "CATALOGUE "))
    }

    @Test
    fun `the factory inputs hold the dependency catalogues and the app ksp resources`() {
        writeJar("libs/lib.jar", "_jar.json")
        projectDir.root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")

        val output = build(
            appScript = """
                dependencies { "debugRuntimeOnly"(files("libs/lib.jar")) }
                // Stands in for the KSP task: the catalogue must exist before the transform runs.
                tasks.register("kspDebugKotlin") {
                    doLast { file("build/generated/ksp/debug/resources/META-INF/hilt-trace/_app.json").apply { parentFile.mkdirs() }.writeText("{}") }
                }
                tasks.register("printInputs") {
                    // Running the transform task also proves the factory is created with its parameters.
                    dependsOn("transformDebugClassesWithAsm")
                    doLast {
                        tasks.getByName("transformDebugClassesWithAsm").inputs.files.files.forEach { root ->
                            root.walkTopDown().filter { it.isFile && "META-INF/hilt-trace" in it.path }.forEach { println("INPUT " + it.name) }
                        }
                    }
                }
            """.trimIndent(),
            task = "printInputs",
        )

        assertEquals(output, listOf("INPUT _app.json", "INPUT _jar.json"), lines(output, "INPUT "))
    }

    @Test
    fun `the create rule switch follows the fastInit property`() {
        projectDir.root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        for ((flag, expected) in listOf("-Pdagger.hilt.fastInit=false" to "false", "-Pdagger.hilt.fastInit=true" to "true")) {
            val output = build(appScript = rulePrinter, task = "printRule", args = listOf(flag))
            assertEquals(output, listOf("RULE $expected"), lines(output, "RULE "))
        }
    }

    @Test
    fun `the plugin adds the compiler to ksp and the runtime to implementation`() {
        val output = build(
            appScript = """
                configurations.create("ksp")
                tasks.register("printAdded") {
                    val ksp = configurations["ksp"].allDependencies.map { it.group + ":" + it.name + ":" + it.version }
                    val impl = configurations["implementation"].allDependencies.map { it.group + ":" + it.name + ":" + it.version }
                    doLast { (ksp + impl).forEach { println("ADDED " + it) } }
                }
            """.trimIndent(),
            task = "printAdded",
        )

        val added = lines(output, "ADDED com.santimattius").map { it.removePrefix("ADDED ") }
        assertEquals(output, 2, added.size)
        assertTrue(output, Regex("com\\.santimattius\\.android:hilt-trace-compiler:\\S+").matches(added[0]))
        assertTrue(output, Regex("com\\.santimattius\\.android:hilt-trace-runtime:\\S+").matches(added[1]))
    }

    private val rulePrinter = """
        tasks.register("printRule") {
            dependsOn("transformDebugClassesWithAsm")
            doLast {
                tasks.getByName("transformDebugClassesWithAsm").inputs.properties
                    .filterKeys { it.endsWith("createRuleEnabled") }.forEach { println("RULE " + it.value) }
            }
        }
    """.trimIndent()

    private fun writeJar(path: String, entry: String) {
        val jar = projectDir.root.resolve(path).apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/hilt-trace/$entry"))
            out.write("{}".toByteArray())
        }
    }

    private fun lines(output: String, prefix: String) = output.lines().filter { it.startsWith(prefix) }.sorted()

    private fun build(appScript: String, include: String? = null, task: String = "printCatalogues", args: List<String> = emptyList()): String {
        val root: File = projectDir.root
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            ${include?.let { "include(\"$it\")" }.orEmpty()}
            """.trimIndent(),
        )
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.santimattius.android.hilt.trace")
            }
            android { namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            $appScript
            $printScript
            """.trimIndent(),
        )
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(listOf(task, "--stacktrace") + args).build().output
    }
}
