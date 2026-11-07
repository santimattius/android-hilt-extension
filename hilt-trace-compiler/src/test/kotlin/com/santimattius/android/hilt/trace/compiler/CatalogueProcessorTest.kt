@file:OptIn(ExperimentalCompilerApi::class)

package com.santimattius.android.hilt.trace.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the catalogue processor with kctfork 0.14.0 on the pinned Kotlin 2.4.21 / KSP 2.3.13. */
class CatalogueProcessorTest {

    private class Compiled(val result: JvmCompilationResult, val files: Map<String, String>) {
        /** The single catalogue file, as trimmed lines without trailing commas. */
        fun lines(): List<String> =
            files.values.single().lines().map { it.trim().trimEnd(',') }
    }

    private fun compile(module: String?, vararg sources: SourceFile): Compiled {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += mutableListOf(CatalogueProcessorProvider())
                if (module != null) processorOptions["hiltTrace.module"] = module
            }
        }
        val result = compilation.compile()
        val files = compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.name == "hilt-trace" }
            .associate { it.name to it.readText() }
        return Compiled(result, files)
    }

    // The real @AndroidEntryPoint lives in hilt-android (an AAR, not resolvable from a JVM module).
    private val androidEntryPointStub = SourceFile.kotlin(
        "AndroidEntryPoint.kt",
        "package dagger.hilt.android\nannotation class AndroidEntryPoint",
    )

    private val mixedModule = SourceFile.kotlin(
        "Mixed.kt",
        """
        package p

        import dagger.Binds
        import dagger.Module
        import dagger.Provides
        import dagger.assisted.Assisted
        import dagger.assisted.AssistedInject
        import dagger.hilt.EntryPoint
        import dagger.hilt.InstallIn
        import dagger.hilt.android.AndroidEntryPoint
        import dagger.hilt.components.SingletonComponent
        import javax.inject.Inject
        import javax.inject.Named
        import javax.inject.Singleton

        class Foo @Inject constructor()

        @Singleton
        class Bar @Inject constructor(val foo: Foo)

        class Widget @AssistedInject constructor(@Assisted val id: Int)

        interface Api
        class ApiImpl @Inject constructor() : Api

        @Module
        @InstallIn(SingletonComponent::class)
        abstract class BindModule {
            @Binds abstract fun bindApi(impl: ApiImpl): Api
        }

        @Module
        @InstallIn(SingletonComponent::class)
        object ProvideModule {
            @Provides @Named("greeting") fun greeting(): String = "hi"
        }

        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface Exposed {
            fun bar(): Bar
            fun greeting(): String
            fun inject(target: MainActivity)
        }

        @AndroidEntryPoint
        class MainActivity
        """.trimIndent(),
    )

    private fun fileNameOf(module: String?, vararg sources: SourceFile): String =
        compile(module, *sources).files.keys.single()

    @Test
    fun mixedModuleEmitsOneBindingPerKind() {
        val compiled = compile(":mixed", androidEntryPointStub, mixedModule)
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode)
        val lines = compiled.lines()

        val bindings = lines.filter { it.startsWith("{\"kind\"") }
        assertEquals(6, bindings.size)
        assertTrue(lines.contains("""{"kind": "INJECT", "type": "p.Foo", "qualifier": null, "scope": null, "owner": "p.Foo", "method": "<init>"}"""))
        assertTrue(lines.contains("""{"kind": "INJECT", "type": "p.Bar", "qualifier": null, "scope": "javax.inject.Singleton", "owner": "p.Bar", "method": "<init>"}"""))
        assertTrue(lines.contains("""{"kind": "ASSISTED_INJECT", "type": "p.Widget", "qualifier": null, "scope": null, "owner": "p.Widget", "method": "<init>"}"""))
        assertTrue(lines.contains("""{"kind": "BINDS", "type": "p.Api", "qualifier": null, "scope": null, "owner": "p.BindModule", "method": "bindApi"}"""))
        assertTrue(lines.contains("""{"kind": "PROVIDES", "type": "kotlin.String", "qualifier": "javax.inject.Named(value=greeting)", "scope": null, "owner": "p.ProvideModule", "method": "greeting"}"""))
    }

    @Test
    fun entryPointsCarryJvmDescriptorAndComponent() {
        val lines = compile(":mixed", androidEntryPointStub, mixedModule).lines()
        val component = "dagger.hilt.components.SingletonComponent"

        assertEquals(3, lines.count { it.startsWith("{\"interface\"") })
        assertTrue(lines.contains("""{"interface": "p.Exposed", "method": "bar", "descriptor": "()Lp/Bar;", "component": "$component"}"""))
        assertTrue(lines.contains("""{"interface": "p.Exposed", "method": "greeting", "descriptor": "()Ljava/lang/String;", "component": "$component"}"""))
        assertTrue(lines.contains("""{"interface": "p.Exposed", "method": "inject", "descriptor": "(Lp/MainActivity;)V", "component": "$component"}"""))
    }

    @Test
    fun androidEntryPointClassesAreInjectionTargets() {
        val lines = compile(":mixed", androidEntryPointStub, mixedModule).lines()
        assertEquals(listOf("""{"type": "p.MainActivity"}"""), lines.filter { it.startsWith("{\"type\"") })
    }

    @Test
    fun emptyModuleDoesNotFailAndWritesAnEmptyCatalogue() {
        val compiled = compile(":empty", SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode)
        val lines = compiled.lines()
        assertTrue(lines.contains("\"schemaVersion\": 1"))
        assertTrue(lines.contains("\"bindings\": []"))
        assertTrue(lines.contains("\"entryPoints\": []"))
        assertTrue(lines.contains("\"injectionTargets\": []"))
    }

    @Test
    fun twoModulesProduceDistinctFiles() {
        val first = fileNameOf(":feature:a", androidEntryPointStub, mixedModule)
        val second = fileNameOf(":feature:b", androidEntryPointStub, mixedModule)
        assertEquals("_feature_a.json", first)
        assertEquals("_feature_b.json", second)
        assertNotEquals(first, second)
    }

    @Test
    fun nestedModuleIdBecomesAJarSafeFileNameAndKeepsTheOriginalId() {
        val compiled = compile(":spike:lib", SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))
        val name = compiled.files.keys.single()
        assertEquals("_spike_lib.json", name)
        assertFalse(name.contains(':'))
        val lines = compiled.lines()
        assertTrue(lines.contains("\"module\": \":spike:lib\""))
        assertTrue(lines.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun idsThatDifferOnlyByColonVersusUnderscoreBothEmitUnderOneFileName() {
        // Decision 5.1b: no hash suffix. Both modules emit; the file name is the same
        // (a packaging collision documented in the KDoc), and each file keeps its own module id.
        val colon = compile(":a:b", SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))
        val underscore = compile(":a_b", SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))

        assertEquals(KotlinCompilation.ExitCode.OK, colon.result.exitCode)
        assertEquals(KotlinCompilation.ExitCode.OK, underscore.result.exitCode)
        assertEquals("_a_b.json", colon.files.keys.single())
        assertEquals("_a_b.json", underscore.files.keys.single())
        assertTrue(colon.lines().contains("\"module\": \":a:b\""))
        assertTrue(underscore.lines().contains("\"module\": \":a_b\""))
    }

    @Test
    fun missingModuleOptionFallsBackToAStableHashAndWarns() {
        val first = compile(null, androidEntryPointStub, mixedModule)
        val again = compile(null, androidEntryPointStub, mixedModule)
        val other = compile(null, SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))

        val name = first.files.keys.single()
        assertTrue(name, Regex("unnamed-[0-9a-f]{8}\\.json").matches(name))
        assertEquals(name, again.files.keys.single())
        assertNotEquals(name, other.files.keys.single())
        assertTrue(first.result.messages.contains("hiltTrace.module"))
        assertTrue(first.lines().any { it.startsWith("\"module\": \"unnamed-") })
    }

    @Test
    fun theProviderIsRegisteredThroughMetaInfServices() {
        val providers = java.util.ServiceLoader.load(com.google.devtools.ksp.processing.SymbolProcessorProvider::class.java)
        assertTrue(providers.any { it is CatalogueProcessorProvider })
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/golden/$name")) { "missing golden $name" }.readText()

    @Test
    fun mixedModuleMatchesTheGoldenFile() {
        val compiled = compile(":mixed", androidEntryPointStub, mixedModule)
        assertEquals(golden("mixed.json"), compiled.files.getValue("_mixed.json"))
    }

    @Test
    fun emptyModuleMatchesTheGoldenFile() {
        val compiled = compile(":empty", SourceFile.kotlin("Plain.kt", "package p\nclass Plain"))
        assertEquals(golden("empty.json"), compiled.files.getValue("_empty.json"))
    }
}
