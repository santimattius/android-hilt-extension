package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real Hilt library module fixture. Gradle entry point:
 * `./gradlew -p hilt-trace-gradle-plugin test --tests '*RealHiltLibraryTest'`.
 *
 * The fixture is a throwaway AGP build with real Hilt 2.60.1 and KSP (no kapt): an application
 * (`@HiltAndroidApp`, Hilt and tracing Gradle plugins) and a library `:lib` (no Hilt Gradle plugin;
 * `ksp(hilt-compiler)`, `ksp(hilt-trace-compiler)` with `hiltTrace.module`) that declares a
 * `@Singleton` `@Provides` binding and an `@EntryPoint`. The processor under test is the current
 * source of `:hilt-trace-compiler`, packaged by the `compilerFixtureJar` task.
 *
 * What it proves (`a real Hilt library module is instrumented in the app debug build only`):
 * - the library's KSP run writes a catalogue holding the real binding and entry point;
 * - the Hilt-generated `SwitchingProvider` of the app component, which creates the library binding, is
 *   instrumented in the debug build: its dex holds the `hilt.create` category and not the bytecode
 *   fallback name `fixture.lib.LibModule_ProvideLibServiceFactory`; the release dex holds neither.
 *
 * What `the catalogue of a real Hilt library names its slices in the app debug dex` proves: the library
 * catalogue reaches the app transform when the library is an Android library *project*, so the binding
 * is named `fixture.lib.LibService` and the `@EntryPoint` accessor is traced
 * (`SingletonComponent.libService`) in debug and not in release. An Android library does not package
 * the KSP resources directory, so the plugin reads it in place (see `HiltTracePlugin`).
 *
 * What it does not prove: the dex checks
 * are string-level (the ASM tests cover the `begin`/`end` call sites), only `SingletonComponent` is used,
 * and the `HiltTrace` runtime is a stub, so nothing is recorded at run time.
 */
class RealHiltLibraryTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val createCategory = "hilt.create"
    private val fallbackSlice = "fixture.lib.LibModule_ProvideLibServiceFactory"
    private val catalogueSlice = "fixture.lib.LibService"
    private val accessorSlice = "SingletonComponent.libService"

    @Test
    fun `a real Hilt library module is instrumented in the app debug build only`() {
        val output = build()

        val catalogue = projectDir.root.resolve("lib/build/generated/ksp/debug/resources/META-INF/hilt-trace/_lib.json")
        assertTrue(output, catalogue.isFile)
        val json = catalogue.readText()
        assertTrue(json, "\"fixture.lib.LibEntryPoint\"" in json && "\"provideLibService\"" in json)
        // The slice is named from the catalogue, so the bytecode fallback name is not in the dex.
        assertEquals(output, listOf(createCategory), listOf(createCategory, fallbackSlice).filter { it in dex("debug") })
        assertEquals(output, emptyList<String>(), listOf(createCategory, fallbackSlice).filter { it in dex("release") })
    }

    @Test
    fun `the catalogue of a real Hilt library names its slices in the app debug dex`() {
        val output = build()

        val markers = listOf(catalogueSlice, accessorSlice)
        assertEquals(output, markers, markers.filter { it in dex("debug") })
        assertEquals(output, emptyList<String>(), markers.filter { it in dex("release") })
    }

    /** The bytes of every dex file the app build of [variant] wrote, as one Latin-1 string so ASCII strings can be searched. */
    private fun dex(variant: String): String = projectDir.root.resolve("build/intermediates/dex/$variant").walkTopDown()
        .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun build(): String {
        val root = projectDir.root
        val compilerJar = checkNotNull(System.getProperty("hiltTrace.compilerJar")) { "hiltTrace.compilerJar is not set" }.replace("\\", "/")
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            include(":lib")
            """.trimIndent(),
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
            ksp { arg("hiltTrace.module", ":app") }
            dependencies {
                implementation(project(":lib"))
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            // The plugin-added coordinates are published later; the runtime is the stub below, the compiler the jar above.
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
            """
            package fixture.app

            import android.app.Application
            import dagger.hilt.android.HiltAndroidApp

            @HiltAndroidApp
            class App : Application()
            """.trimIndent(),
        )
        root.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                id("com.android.library")
                id("com.google.devtools.ksp")
            }
            android {
                namespace = "fixture.lib"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
            ksp { arg("hiltTrace.module", ":lib") }
            dependencies {
                implementation("com.google.dagger:hilt-android:$HILT")
                ksp("com.google.dagger:hilt-compiler:$HILT")
                ksp(files("$compilerJar"))
            }
            """.trimIndent(),
        )
        root.resolve("lib/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText("<manifest />")
        root.resolve("lib/src/main/kotlin/fixture/lib/LibModule.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package fixture.lib

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.EntryPoint
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import javax.inject.Singleton

            class LibService

            @Module
            @InstallIn(SingletonComponent::class)
            object LibModule {
                @Provides
                @Singleton
                fun provideLibService(): LibService = LibService()
            }

            @EntryPoint
            @InstallIn(SingletonComponent::class)
            interface LibEntryPoint {
                fun libService(): LibService
            }
            """.trimIndent(),
        )
        return GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(":assembleDebug", ":assembleRelease", "--stacktrace").build().output
    }

    private companion object {
        const val HILT = "2.60.1"
    }
}
