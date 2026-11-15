package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * A library of a build nested in an included build is never read from another project's output.
 *
 * Fixture: the main build has an Android library `:lib` whose module `fixture.nested.NestedModule`
 * provides `WrongService`; the app depends only on the included build `libb`, which includes the build
 * `libc` whose project `:lib` has the same module class and method but provides `NestedService`. Both
 * projects have the project path `:lib`, and the main `:lib` is built in the same run.
 *
 * What it proves: the app dex never takes the slice name from the main build's `:lib` catalogue, and the
 * library that is not located is reported by one warning naming it. Nested included builds are not read
 * (their directory is not known to the app's build), so their slices keep the bytecode names.
 */
class NestedIncludedBuildTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `a library of a nested included build is not read from the main build project with the same path`() {
        val output = build()

        val dex = projectDir.root.resolve("build/intermediates/dex/debug").walkTopDown()
            .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue("the main :lib catalogue was read", "fixture.wrong.WrongService" !in dex)
        val warnings = output.lines().filter { it.startsWith("Hilt Trace:") && "not read" in it }
        assertEquals(output, 1, warnings.size)
        assertTrue(output, ":libb:libc" in warnings.single() && ":lib" in warnings.single())
    }

    private fun build(): String {
        val root = projectDir.root
        val compilerJar = checkNotNull(System.getProperty("hiltTrace.compilerJar")) { "hiltTrace.compilerJar is not set" }.replace("\\", "/")
        val repos = "dependencyResolutionManagement { repositories { google(); mavenCentral() } }"
        root.resolve("settings.gradle.kts").writeText("$repos\nrootProject.name = \"fixture\"\ninclude(\":lib\")\nincludeBuild(\"libb\")")
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
                implementation("fixture:libb")
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
        library(root.resolve("lib"), "wrong", "WrongService", compilerJar)
        library(root.resolve("libb/libc/lib"), "nested", "NestedService", compilerJar)
        root.resolve("libb/settings.gradle.kts").writeText("$repos\nrootProject.name = \"libb\"\nincludeBuild(\"libc\")")
        root.resolve("libb/libc/settings.gradle.kts").writeText("$repos\nrootProject.name = \"libc\"\ninclude(\":lib\")")
        root.resolve("libb/gradle.properties").writeText("android.useAndroidX=true\n")
        root.resolve("libb/libc/gradle.properties").writeText("android.useAndroidX=true\n")
        root.resolve("libb/build.gradle.kts").writeText(script("fixture.libb", "add(\"api\", \"fixture:lib\")"))
        root.resolve("libb/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("libb/src/main/kotlin/fixture/libb/Libb.kt").apply { parentFile.mkdirs() }.writeText("package fixture.libb\n\nclass Libb\n")
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(":lib:kspDebugKotlin", ":assembleDebug", "--stacktrace").build().output
    }

    /** A library at [dir] whose module `fixture.nested.NestedModule` provides [service] of `fixture.[pkg]`. */
    private fun library(dir: File, pkg: String, service: String, compilerJar: String) {
        dir.resolve("build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            script(
                if (pkg == "wrong") "fixture.wronglib" else "fixture.nestedlib",
                "add(\"implementation\", \"com.google.dagger:hilt-android:$HILT\")\n" +
                    "add(\"ksp\", \"com.google.dagger:hilt-compiler:$HILT\")\nadd(\"ksp\", files(\"$compilerJar\"))",
                ksp = true,
            ),
        )
        dir.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        dir.resolve("src/main/kotlin/fixture/nested/Module.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package fixture.nested

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.EntryPoint
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import fixture.$pkg.$service
            import javax.inject.Singleton

            @Module
            @InstallIn(SingletonComponent::class)
            object NestedModule {
                @Provides
                @Singleton
                fun provideService(): $service = $service()
            }

            @EntryPoint
            @InstallIn(SingletonComponent::class)
            interface NestedEntryPoint {
                fun service(): $service
            }
            """.trimIndent(),
        )
        dir.resolve("src/main/kotlin/fixture/$pkg/$service.kt").apply { parentFile.mkdirs() }
            .writeText("package fixture.$pkg\n\nclass $service\n")
    }

    /** An included build applies the plugins from the classpath TestKit injects into the root build only. */
    private fun script(namespace: String, dependencies: String, ksp: Boolean = false): String {
        val metadata = checkNotNull(javaClass.classLoader.getResource("plugin-under-test-metadata.properties")) { "no plugin metadata" }
        val classpath = Properties().apply { metadata.openStream().use { load(it) } }
            .getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }
        val kspSetup = if (ksp) {
            "apply(plugin = \"com.google.devtools.ksp\")\n" +
                "(extensions.getByName(\"ksp\") as com.google.devtools.ksp.gradle.KspExtension).arg(\"hiltTrace.module\", \":nested\")"
        } else {
            ""
        }
        return """
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies { classpath(files($classpath)) }
            }
            apply(plugin = "com.android.library")
            $kspSetup
            group = "fixture"
            extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
                namespace = "$namespace"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
            dependencies {
                $dependencies
            }
        """.trimIndent()
    }

    private companion object {
        const val HILT = "2.60.1"
    }
}
