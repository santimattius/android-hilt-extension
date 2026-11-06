@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package spike.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 0.8 probe: kctfork 0.14.0 drives a KSP processor on Kotlin 2.4.21 / KSP 2.3.13. */
class CatalogueSpikeTest {
    private fun compile(vararg classes: String, module: String): Pair<JvmCompilationResult, List<File>> {
        val compilation = KotlinCompilation().apply {
            sources = classes.map { SourceFile.kotlin("$it.kt", "package p\nclass $it") }
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += mutableListOf(SpikeProcessorProvider())
                processorOptions["hiltTrace.module"] = module
            }
        }
        val result = compilation.compile()
        val json = compilation.workingDir.walkTopDown().filter { it.isFile && it.parentFile.name == "hilt-trace" }.toList()
        return result to json
    }

    @Test
    fun processorWritesOneCatalogueResourcePerModule() {
        val (result, json) = compile("Alpha", "Beta", module = ":feature")
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertEquals(1, json.size)
        assertEquals("feature.json", json.single().name)
        assertTrue(json.single().readText().contains("\"classes\":2"))
    }

    @Test
    fun emptyModuleStillProducesAFile() {
        val (result, json) = compile(module = ":empty")
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(json.single().readText().contains("\"classes\":0"))
    }

    @Test
    fun nestedModulePathBecomesAJarSafeFileName() {
        val (_, json) = compile("Gamma", module = ":spike:lib")
        assertEquals("spike_lib.json", json.single().name)
    }
}
