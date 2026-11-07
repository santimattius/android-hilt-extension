package com.santimattius.android.hilt.trace.plugin

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TestKit build that executes the artifact transform on real dependency forms: a jar and a classes
 * directory. The build script resolves the artifact type the plugin registers and prints the
 * catalogue files it finds.
 */
class CatalogueTransformWiringTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val printScript = """
        tasks.register("printCatalogues") {
            val view = configurations["debugRuntimeClasspath"].incoming.artifactView {
                attributes.attribute(Attribute.of("artifactType", String::class.java), "hilt-trace-catalogue")
            }.files
            doLast { view.files.forEach { root -> root.walkTopDown().filter { it.isFile }.forEach { println("CATALOGUE " + it.name) } } }
        }
    """.trimIndent()

    @Test
    fun `jar and directory forms are both read`() {
        val jar = projectDir.root.resolve("libs/lib.jar").apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/hilt-trace/_jar.json"))
            out.write("{}".toByteArray())
        }
        projectDir.root.resolve("classes/META-INF/hilt-trace").mkdirs()
        projectDir.root.resolve("classes/META-INF/hilt-trace/_dir.json").writeText("{}")

        val output = build(appScript = """dependencies { "debugRuntimeOnly"(files("libs/lib.jar", "classes")) }""")

        assertEquals(output, listOf("CATALOGUE _dir.json", "CATALOGUE _jar.json"), catalogueLines(output))
    }

    private fun catalogueLines(output: String) = output.lines().filter { it.startsWith("CATALOGUE ") }.sorted()

    private fun build(appScript: String): String {
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
                id("com.santimattius.android.hilt.trace")
            }
            android { namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 } }
            $appScript
            $printScript
            """.trimIndent(),
        )
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("printCatalogues", "--stacktrace").build().output
    }
}
