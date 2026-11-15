package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * A current-build Android library, an app-only product flavor and an included-build library in one build.
 *
 * What it proves: the app `freeDebug` dex names the slices of both libraries from their catalogues (the
 * current-build `:lib` and the included `incl`, each with its own binding) and nothing is warned. It guards
 * the combination; it does not cover a nested included build (`NestedIncludedBuildTest`).
 */
class MixedLibrariesTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `a current-build library and an included-build library are both named under an app-only flavor`() {
        val output = build()

        val dex = projectDir.root.resolve("build/intermediates/dex/freeDebug").walkTopDown()
            .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue("current-build catalogue lost", "fixture.a.AService" in dex && "fixture.a.AServiceModule_ProvideFactory" !in dex)
        assertTrue("included-build catalogue lost", "fixture.b.BService" in dex && "fixture.b.BServiceModule_ProvideFactory" !in dex)
        assertTrue(output, "Hilt Trace" !in output)
    }

    @Test
    fun `the mixed libraries are named again when the configuration cache is reused`() {
        val first = build("--configuration-cache")
        // The dex is rebuilt on the reused configuration, so the names come from this run.
        projectDir.root.resolve("build/intermediates/dex").deleteRecursively()
        val second = run("--configuration-cache")

        assertTrue(first, "Configuration cache entry stored" in first)
        assertTrue(second, "Reusing configuration cache" in second)
        val dex = projectDir.root.resolve("build/intermediates/dex/freeDebug").walkTopDown()
            .filter { it.isFile && it.extension == "dex" }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(second, "fixture.a.AService" in dex && "fixture.b.BService" in dex)
        assertTrue(second, "Hilt Trace" !in second && "resolved during configuration time" !in second)
    }

    private fun run(vararg extra: String): String = GradleRunner.create().withProjectDir(projectDir.root).withPluginClasspath()
        .withArguments(":assembleFreeDebug", "--stacktrace", *extra).build().output

    private fun build(vararg extra: String): String {
        val root = projectDir.root
        val compilerJar = checkNotNull(System.getProperty("hiltTrace.compilerJar")) { "hiltTrace.compilerJar is not set" }.replace("\\", "/")
        val repos = "dependencyResolutionManagement { repositories { google(); mavenCentral() } }"
        root.resolve("settings.gradle.kts").writeText("$repos\nrootProject.name = \"fixture\"\ninclude(\":lib\")\nincludeBuild(\"incl\")")
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
                flavorDimensions += "tier"
                productFlavors { create("free") { dimension = "tier" } }
            }
            hiltTrace { variants.set(listOf("freeDebug")) }
            ksp { arg("hiltTrace.module", ":app") }
            dependencies {
                implementation(project(":lib"))
                implementation("fixture:incl")
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
        library(root.resolve("lib"), "a", "AService", compilerJar)
        library(root.resolve("incl"), "b", "BService", compilerJar)
        root.resolve("incl/settings.gradle.kts").writeText("$repos\nrootProject.name = \"incl\"")
        root.resolve("incl/gradle.properties").writeText("android.useAndroidX=true\n")
        return run(*extra)
    }

    /**
     * An Android library at [dir] with module id `:[pkg]` that provides [service] of `fixture.[pkg]`. The
     * included build (`b`) applies the plugins from the classpath TestKit injects into the root build only.
     */
    private fun library(dir: File, pkg: String, service: String, compilerJar: String) {
        val included = pkg == "b"
        val metadata = checkNotNull(javaClass.classLoader.getResource("plugin-under-test-metadata.properties")) { "no plugin metadata" }
        val classpath = Properties().apply { metadata.openStream().use { load(it) } }
            .getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }
        val head = if (included) {
            "buildscript {\n    repositories { google(); mavenCentral() }\n    dependencies { classpath(files($classpath)) }\n}\n" +
                "apply(plugin = \"com.android.library\")\napply(plugin = \"com.google.devtools.ksp\")\ngroup = \"fixture\""
        } else {
            "plugins { id(\"com.android.library\"); id(\"com.google.devtools.ksp\") }"
        }
        val config = if (included) "extensions.configure<com.android.build.api.dsl.LibraryExtension>(\"android\")" else "android"
        val kspArg = if (included) {
            "(extensions.getByName(\"ksp\") as com.google.devtools.ksp.gradle.KspExtension).arg(\"hiltTrace.module\", \":$pkg\")"
        } else {
            "ksp { arg(\"hiltTrace.module\", \":$pkg\") }"
        }
        val deps = if (included) {
            "add(\"implementation\", \"com.google.dagger:hilt-android:$HILT\")\n" +
                "add(\"ksp\", \"com.google.dagger:hilt-compiler:$HILT\")\nadd(\"ksp\", files(\"$compilerJar\"))"
        } else {
            "implementation(\"com.google.dagger:hilt-android:$HILT\")\n" +
                "ksp(\"com.google.dagger:hilt-compiler:$HILT\")\nksp(files(\"$compilerJar\"))"
        }
        dir.resolve("build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            $head
            $config {
                namespace = "fixture.lib$pkg"; compileSdk = 36; defaultConfig { minSdk = 24 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
            $kspArg
            dependencies {
                $deps
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
