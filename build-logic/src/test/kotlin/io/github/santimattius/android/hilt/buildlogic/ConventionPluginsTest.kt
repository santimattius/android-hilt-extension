package io.github.santimattius.android.hilt.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit checks that every convention plugin resolves by id and applies the shared configuration
 * the library modules rely on. Each test builds a throwaway project that only applies the plugin.
 */
class ConventionPluginsTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `android library convention applies AGP with SDK levels from properties`() {
        val output = runReport(
            properties = "androidMinSdkVersion=24\nandroidLibraryCompileSdkVersion=36",
            buildScript = """
                plugins { id("hiltext.android-library") }
                android { namespace = "sample.android" }
                val minSdk = android.defaultConfig.minSdk
                val compileSdk = android.compileSdk
                tasks.register("report") {
                    // Read at execution time: AGP finalizes compile options after evaluation.
                    doLast {
                        val javaTarget = android.compileOptions.targetCompatibility
                        println("minSdk=${'$'}minSdk compileSdk=${'$'}compileSdk java=${'$'}javaTarget")
                    }
                }
            """.trimIndent(),
        )
        assertTrue(output, "minSdk=24 compileSdk=36 java=17" in output)
    }

    @Test
    fun `android library convention reads different SDK levels`() {
        val output = runReport(
            properties = "androidMinSdkVersion=26\nandroidLibraryCompileSdkVersion=34",
            buildScript = """
                plugins { id("hiltext.android-library") }
                android { namespace = "sample.android" }
                val minSdk = android.defaultConfig.minSdk
                val compileSdk = android.compileSdk
                tasks.register("report") {
                    doLast { println("minSdk=${'$'}minSdk compileSdk=${'$'}compileSdk") }
                }
            """.trimIndent(),
        )
        assertTrue(output, "minSdk=26 compileSdk=34" in output)
    }

    @Test
    fun `jvm library convention applies unversioned Kotlin JVM targeting 17`() {
        val output = runReport(
            properties = "",
            buildScript = """
                plugins { id("hiltext.jvm-library") }
                val kotlinApplied = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
                val kotlinTarget = kotlin.compilerOptions.jvmTarget.get().target
                val javaTarget = java.targetCompatibility
                tasks.register("report") {
                    doLast { println("kotlin=${'$'}kotlinApplied kotlinTarget=${'$'}kotlinTarget java=${'$'}javaTarget") }
                }
            """.trimIndent(),
        )
        assertTrue(output, "kotlin=true kotlinTarget=17 java=17" in output)
    }

    @Test
    fun `jvm library convention pins the minimum Kotlin language level and core libraries`() {
        val output = runReport(
            properties = "",
            buildScript = """
                plugins { id("hiltext.jvm-library") }
                tasks.register("report") {
                    doLast {
                        val options = kotlin.compilerOptions
                        println(
                            "language=${'$'}{options.languageVersion.get().version} " +
                                "api=${'$'}{options.apiVersion.get().version} " +
                                "core=${'$'}{kotlin.coreLibrariesVersion}",
                        )
                    }
                }
            """.trimIndent(),
        )
        assertTrue(output, "language=2.2 api=2.2 core=2.2.21" in output)
    }

    @Test
    fun `android library convention pins the minimum Kotlin language level and core libraries`() {
        val output = runReport(
            properties = "androidMinSdkVersion=24\nandroidLibraryCompileSdkVersion=36",
            buildScript = """
                plugins { id("hiltext.android-library") }
                android { namespace = "sample.android" }
                val kotlinExtension = project.extensions.getByName("kotlin")
                tasks.register("report") {
                    doLast {
                        val options = (kotlinExtension as org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions<*>)
                            .compilerOptions
                        val core = (kotlinExtension as org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension)
                            .coreLibrariesVersion
                        println(
                            "language=${'$'}{options.languageVersion.get().version} " +
                                "api=${'$'}{options.apiVersion.get().version} core=${'$'}core",
                        )
                    }
                }
            """.trimIndent(),
        )
        assertTrue(output, "language=2.2 api=2.2 core=2.2.21" in output)
    }

    @Test
    fun `published POM depends on the pinned kotlin-stdlib, not the Gradle plugin's Kotlin version`() {
        runReport(
            task = "generatePomFileForMavenPublication",
            properties = "libraryGroupId=com.example\nlibraryVersion=9.9.9",
            buildScript = """
                plugins {
                    id("hiltext.jvm-library")
                    id("hiltext.publishing")
                }
            """.trimIndent(),
        )
        val pom = File(projectDir.root, "build/publications/maven/pom-default.xml").readText()
        val stdlibVersion = Regex(
            "<artifactId>kotlin-stdlib</artifactId>\\s*<version>([^<]+)</version>",
        ).find(pom)?.groupValues?.get(1)
        assertEquals(pom, "2.2.21", stdlibVersion)
    }

    @Test
    fun `publishing convention produces the POM with shared coordinates and metadata`() {
        runReport(
            task = "generatePomFileForMavenPublication",
            properties = "libraryGroupId=com.example\nlibraryVersion=9.9.9",
            buildScript = """
                plugins {
                    id("hiltext.jvm-library")
                    id("hiltext.publishing")
                }
            """.trimIndent(),
        )
        val pom = File(projectDir.root, "build/publications/maven/pom-default.xml").readText()
        assertTrue(pom, "<groupId>com.example</groupId>" in pom)
        assertTrue(pom, "<artifactId>sample-lib</artifactId>" in pom)
        assertTrue(pom, "<version>9.9.9</version>" in pom)
        assertTrue(pom, "<name>sample-lib</name>" in pom)
        assertTrue(pom, "The Apache License, Version 2.0" in pom)
        assertTrue(pom, "<id>santimattius</id>" in pom)
        assertTrue(pom, "scm:git:git://github.com/santimattius/android-hilt-extension.git" in pom)
    }

    @Test
    fun `publishing convention honors POM property overrides`() {
        runReport(
            task = "generatePomFileForMavenPublication",
            properties = "libraryGroupId=com.example\nlibraryVersion=1.2.3\nPOM_DESCRIPTION=Overridden description\nPOM_DEVELOPER_ID=someone",
            buildScript = """
                plugins {
                    id("hiltext.jvm-library")
                    id("hiltext.publishing")
                }
            """.trimIndent(),
        )
        val pom = File(projectDir.root, "build/publications/maven/pom-default.xml").readText()
        assertTrue(pom, "<description>Overridden description</description>" in pom)
        assertTrue(pom, "<id>someone</id>" in pom)
        assertTrue(pom, "<version>1.2.3</version>" in pom)
    }

    @Test
    fun `publishing convention signs publications by default`() {
        val output = runReport(
            properties = "libraryGroupId=com.example\nlibraryVersion=1.0.0",
            buildScript = signingReportScript(),
        )
        assertTrue(output, "signing=true" in output)
    }

    @Test
    fun `publishing convention skips signing only on explicit opt-out`() {
        val output = runReport(
            properties = "libraryGroupId=com.example\nlibraryVersion=1.0.0\nhiltext.skipSigning=true",
            buildScript = signingReportScript(),
        )
        assertTrue(output, "signing=false" in output)
    }

    private fun signingReportScript() = """
        plugins {
            id("hiltext.jvm-library")
            id("hiltext.publishing")
        }
        tasks.register("report") {
            doLast { println("signing=${'$'}{tasks.names.any { it.startsWith("sign") }}") }
        }
    """.trimIndent()

    private fun runReport(properties: String, buildScript: String, task: String = "report"): String {
        projectDir.newFile("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "sample-lib"
            """.trimIndent(),
        )
        projectDir.newFile("build.gradle.kts").writeText(buildScript)
        projectDir.newFile("gradle.properties").writeText("$properties\nandroid.useAndroidX=true\n")
        sdkDir()?.let { projectDir.newFile("local.properties").writeText("sdk.dir=$it\n") }
        return GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(task, "--stacktrace")
            .build()
            .output
    }

    private fun sdkDir(): String? =
        System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
}
