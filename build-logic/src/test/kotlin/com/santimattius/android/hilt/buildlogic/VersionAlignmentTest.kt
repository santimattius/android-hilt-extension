package com.santimattius.android.hilt.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit checks for the `verifyVersionAlignment` task: every `libraryVersion` default must match,
 * and the task is part of `check`. Each test builds a throwaway root project with one properties
 * file per build.
 */
class VersionAlignmentTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `matching defaults pass`() {
        val result = runAlignment(root = "1.0.0-alpha01", included = mapOf("build-logic" to "1.0.0-alpha01"), expectFailure = false)
        assertTrue(result.output, "verifyVersionAlignment" in result.output)
        assertEquals(result.output, "SUCCESS", result.outcome)
    }

    @Test
    fun `drifting included build default fails and names the file`() {
        val result = runAlignment(
            root = "1.0.0-alpha01",
            included = mapOf("build-logic" to "1.0.0-alpha01", "hilt-trace-gradle-plugin" to "1.0.0-alpha02"),
            expectFailure = true,
        )
        assertEquals(result.output, "FAILED", result.outcome)
        assertTrue(result.output, "hilt-trace-gradle-plugin/gradle.properties" in result.output)
        assertTrue(result.output, "1.0.0-alpha02" in result.output)
    }

    @Test
    fun `a properties file without libraryVersion fails`() {
        val result = runAlignment(
            root = "2.0.0",
            included = mapOf("build-logic" to null),
            expectFailure = true,
        )
        assertEquals(result.output, "FAILED", result.outcome)
        assertTrue(result.output, "libraryVersion" in result.output)
    }

    @Test
    fun `check depends on verifyVersionAlignment`() {
        val result = runAlignment(
            root = "1.0.0",
            included = mapOf("build-logic" to "9.9.9"),
            expectFailure = true,
            task = "check",
        )
        assertEquals(result.output, "FAILED", result.outcome)
        assertTrue(result.output, "9.9.9" in result.output)
    }

    private data class Outcome(val output: String, val outcome: String)

    private fun runAlignment(
        root: String,
        included: Map<String, String?>,
        expectFailure: Boolean,
        task: String = "verifyVersionAlignment",
    ): Outcome {
        projectDir.newFile("settings.gradle.kts").writeText("rootProject.name = \"sample-root\"\n")
        projectDir.newFile("gradle.properties").writeText("libraryVersion=$root\n")
        val files = included.map { (dir, version) ->
            File(projectDir.root, dir).mkdirs()
            File(projectDir.root, "$dir/gradle.properties").writeText(
                if (version == null) "someOtherProperty=1\n" else "libraryVersion=$version\n",
            )
            "\"$dir/gradle.properties\""
        }
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins { id("hiltext.version-alignment") }
            tasks.named<com.santimattius.android.hilt.buildlogic.VerifyVersionAlignment>("verifyVersionAlignment") {
                propertyFiles.from(file("gradle.properties"), ${files.joinToString(", ") { "file($it)" }})
            }
            """.trimIndent(),
        )
        val runner = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(task, "--stacktrace", "--configuration-cache")
        val result = if (expectFailure) runner.buildAndFail() else runner.build()
        val outcome = result.task(":verifyVersionAlignment")?.outcome?.name ?: "NOT_RUN"
        return Outcome(result.output, outcome)
    }
}
