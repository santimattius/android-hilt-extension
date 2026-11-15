package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Two Android libraries that declare the same `hiltTrace.module` write the same catalogue file name
 * (`_shared.json`). The collector task merges the catalogues of all libraries into one directory, so
 * neither may overwrite the other.
 *
 * What it proves: both libraries name their slices from their own catalogue in the app debug dex, and the
 * shared module id is reported by one warning naming both libraries.
 */
class CatalogueCollisionTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `two libraries with the same module id keep their own catalogue and are reported once`() {
        val output = build()

        val dex = projectDir.root.resolve("build/intermediates/dex/debug").walkTopDown()
            .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        // A slice named from the catalogue is the type; one not found there keeps the bytecode factory name.
        assertTrue("liba catalogue lost", "fixture.a.AService" in dex && "fixture.a.AServiceModule_ProvideFactory" !in dex)
        assertTrue("libb catalogue lost", "fixture.b.BService" in dex && "fixture.b.BServiceModule_ProvideFactory" !in dex)
        val warnings = output.lines().filter { it.startsWith("Hilt Trace:") && "same catalogue file" in it }
        assertEquals(output, 1, warnings.size)
        assertTrue(output, "libraries :liba and :libb " in warnings.single())
    }

    private fun build(): String {
        val root = projectDir.root
        val compilerJar = checkNotNull(System.getProperty("hiltTrace.compilerJar")) { "hiltTrace.compilerJar is not set" }.replace("\\", "/")
        root.resolve("settings.gradle.kts").writeText(
            "dependencyResolutionManagement { repositories { google(); mavenCentral() } }\n" +
                "rootProject.name = \"fixture\"\ninclude(\":liba\", \":libb\")",
        )
        root.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        System.getenv("ANDROID_HOME")?.let { root.resolve("local.properties").writeText("sdk.dir=$it") }
        root.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.google.devtools.ksp")
                id("com.google.dagger.hilt.android")
                id("io.github.santimattius.android.hilt.trace")
            }
            android {
                namespace = "fixture.app"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                lint { checkReleaseBuilds = false }
            }
            hiltTrace { variants.set(listOf("debug")) }
            ksp { arg("hiltTrace.module", ":app") }
            dependencies {
                implementation(project(":liba"))
                implementation(project(":libb"))
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            configurations.all { exclude(group = "io.github.santimattius.android") }
            """.trimIndent(),
        )
        root.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:name=\".App\" /></manifest>",
        )
        root.resolve("src/main/java/io/github/santimattius/android/hilt/trace/HiltTrace.java").apply { parentFile.mkdirs() }.writeText(
            "package io.github.santimattius.android.hilt.trace; public final class HiltTrace { " +
                "public static Object begin(String category, String name) { return name; } public static void end(Object token) {} }",
        )
        root.resolve("src/main/kotlin/fixture/app/App.kt").apply { parentFile.mkdirs() }.writeText(
            "package fixture.app\n\nimport android.app.Application\nimport dagger.hilt.android.HiltAndroidApp\n\n@HiltAndroidApp\nclass App : Application()\n",
        )
        library(root.resolve("liba"), "a", "AService", compilerJar)
        library(root.resolve("libb"), "b", "BService", compilerJar)
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(":assembleDebug", "--stacktrace").build().output
    }

    /** A library at [dir] that declares the module id `:shared` and provides [service] of `fixture.[pkg]`. */
    private fun library(dir: File, pkg: String, service: String, compilerJar: String) {
        dir.resolve("build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                id("com.android.library")
                id("com.google.devtools.ksp")
            }
            android {
                namespace = "fixture.lib$pkg"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
            ksp { arg("hiltTrace.module", ":shared") }
            dependencies {
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            """.trimIndent(),
        )
        dir.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        dir.resolve("src/main/kotlin/fixture/$pkg/Module.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package fixture.$pkg

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.EntryPoint
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import javax.inject.Singleton

            class $service

            @Module
            @InstallIn(SingletonComponent::class)
            object ${service}Module {
                @Provides
                @Singleton
                fun provide(): $service = $service()
            }

            @EntryPoint
            @InstallIn(SingletonComponent::class)
            interface ${service}EntryPoint {
                fun get$service(): $service
            }
            """.trimIndent(),
        )
    }

    private companion object {
        const val HILT = "2.60.1"
    }
}
