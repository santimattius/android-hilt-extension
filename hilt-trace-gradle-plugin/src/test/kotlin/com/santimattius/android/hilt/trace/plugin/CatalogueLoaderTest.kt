package com.santimattius.android.hilt.trace.plugin

import com.santimattius.android.hilt.trace.compiler.catalogue.Binding
import com.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import com.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import com.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import com.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import com.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The loader reads files written by the real [JsonWriter], so both sides agree on the format. */
class CatalogueLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun binding(method: String, scope: String? = null) =
        Binding(BindingKind.PROVIDES, "p.Foo", qualifier = "javax.inject.Named(value=x)", scope = scope, owner = "p.M", method = method)

    private fun catalogue(module: String, vararg bindings: Binding) = Catalogue(
        module,
        bindings.toList(),
        listOf(EntryPoint("p.Exposed", "foo", "()Lp/Foo;", "dagger.hilt.components.SingletonComponent")),
        listOf(InjectionTarget("p.MainActivity")),
    )

    /** A root laid out like a transform output or a KSP resources directory. */
    private fun root(name: String, vararg files: Pair<String, String>): File = tmp.newFolder(name).also { root ->
        for ((fileName, text) in files) {
            root.resolve("META-INF/hilt-trace").mkdirs()
            root.resolve("META-INF/hilt-trace/$fileName").writeText(text)
        }
    }

    @Test
    fun `catalogues of several roots are merged into one`() {
        val jarRoot = root("jar", "_lib.json" to JsonWriter.write(catalogue(":lib", binding("fromJar", scope = "javax.inject.Singleton"))))
        val kspRoot = root("ksp", "_app.json" to JsonWriter.write(catalogue(":app", binding("fromApp"))))

        val result = loadCatalogue(listOf(jarRoot, kspRoot))

        assertEquals(listOf("fromJar", "fromApp"), result.bindings.map { it.method })
        assertEquals("javax.inject.Singleton", result.bindings[0].scope)
        assertEquals("javax.inject.Named(value=x)", result.bindings[1].qualifier)
        assertEquals(listOf("()Lp/Foo;"), result.entryPoints.map { it.descriptor })
        assertEquals(listOf("p.MainActivity"), result.injectionTargets.map { it.type })
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    @Test
    fun `missing and empty roots contribute nothing and do not warn`() {
        val withCatalogue = root("jar", "_lib.json" to JsonWriter.write(catalogue(":lib", binding("kept"))))

        val result = loadCatalogue(listOf(File(tmp.root, "absent"), tmp.newFolder("empty"), withCatalogue))

        assertEquals(listOf("kept"), result.bindings.map { it.method })
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    @Test
    fun `an unreadable file is skipped with one warning and the others are kept`() {
        val result = loadCatalogue(
            listOf(
                root("bad", "_bad.json" to "{ this is not json"),
                root("good", "_good.json" to JsonWriter.write(catalogue(":good", binding("kept")))),
            ),
        )

        assertEquals(listOf("kept"), result.bindings.map { it.method })
        assertEquals(result.warnings.toString(), 1, result.warnings.size)
        assertTrue(result.warnings.single(), "_bad.json" in result.warnings.single())
    }

    @Test
    fun `an unknown schema is skipped with one warning and the others are kept`() {
        val future = JsonWriter.write(catalogue(":future", binding("ignored"))).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val result = loadCatalogue(
            listOf(
                root("future", "_future.json" to future),
                root("good", "_good.json" to JsonWriter.write(catalogue(":good", binding("kept")))),
            ),
        )

        assertEquals(listOf("kept"), result.bindings.map { it.method })
        assertEquals(result.warnings.toString(), 1, result.warnings.size)
        assertTrue(result.warnings.single(), "schemaVersion 2" in result.warnings.single())
    }
}
