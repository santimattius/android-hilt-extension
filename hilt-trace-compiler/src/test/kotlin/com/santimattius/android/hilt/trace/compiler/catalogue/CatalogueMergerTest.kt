package com.santimattius.android.hilt.trace.compiler.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueMergerTest {

    private fun binding(
        method: String,
        type: String = "p.Foo",
        scope: String? = null,
        owner: String = "p.M",
    ) = Binding(BindingKind.PROVIDES, type, qualifier = null, scope = scope, owner = owner, method = method)

    private fun entryPoint(method: String, component: String = "SingletonComponent") =
        EntryPoint("p.Exposed", method, "()Lp/Foo;", component)

    private fun catalogue(
        module: String,
        bindings: List<Binding> = emptyList(),
        entryPoints: List<EntryPoint> = emptyList(),
        targets: List<InjectionTarget> = emptyList(),
    ) = Catalogue(module, bindings, entryPoints, targets)

    private fun input(origin: String, catalogue: Catalogue, fileName: String = "$origin.json") =
        CatalogueInput(origin, fileName, schemaVersion = 1, catalogue = catalogue)

    @Test
    fun identicalEntriesAreDeduplicatedAcrossSources() {
        val shared = binding("shared")
        val result = CatalogueMerger.merge(
            listOf(
                input("jar", catalogue(":a", listOf(shared, binding("onlyJar")), listOf(entryPoint("x")), listOf(InjectionTarget("p.A")))),
                input("dir", catalogue(":a", listOf(shared, binding("onlyDir")), listOf(entryPoint("x")), listOf(InjectionTarget("p.A")))),
            ),
        )

        assertEquals(listOf("shared", "onlyJar", "onlyDir"), result.bindings.map { it.method })
        assertEquals(listOf("x"), result.entryPoints.map { it.method })
        assertEquals(listOf("p.A"), result.injectionTargets.map { it.type })
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    @Test
    fun firstWinsOnConflictAndWarns() {
        val result = CatalogueMerger.merge(
            listOf(
                input("first", catalogue(":a", listOf(binding("provide", scope = "javax.inject.Singleton")), listOf(entryPoint("x", "SingletonComponent")))),
                input("second", catalogue(":b", listOf(binding("provide", scope = null)), listOf(entryPoint("x", "ActivityComponent")))),
            ),
        )

        assertEquals("javax.inject.Singleton", result.bindings.single().scope)
        assertEquals("SingletonComponent", result.entryPoints.single().component)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("p.M.provide") && it.contains("second") })
        assertTrue(result.warnings.any { it.contains("p.Exposed.x") && it.contains("second") })
    }

    @Test
    fun unknownSchemaVersionIsSkippedWithAWarning() {
        val result = CatalogueMerger.merge(
            listOf(
                CatalogueInput("future", "future.json", schemaVersion = 2, catalogue = null),
                input("known", catalogue(":a", listOf(binding("provide")))),
            ),
        )

        assertEquals(listOf("provide"), result.bindings.map { it.method })
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("future") && result.warnings.single().contains("2"))
    }

    @Test
    fun collidingFileNamesWithConflictingModuleFieldsWarnButBothMerge() {
        val result = CatalogueMerger.merge(
            listOf(
                input("jar-a", catalogue(":a:b", listOf(binding("one"))), fileName = "_a_b.json"),
                input("jar-b", catalogue(":a_b", listOf(binding("two"))), fileName = "_a_b.json"),
            ),
        )

        assertEquals(listOf("one", "two"), result.bindings.map { it.method })
        assertEquals(1, result.warnings.size)
        val warning = result.warnings.single()
        assertTrue(warning, warning.contains("_a_b.json") && warning.contains(":a:b") && warning.contains(":a_b"))
    }

    @Test
    fun sameFileNameWithTheSameModuleIsNotACollision() {
        val result = CatalogueMerger.merge(
            listOf(
                input("jar", catalogue(":a:b", listOf(binding("one"))), fileName = "_a_b.json"),
                input("dir", catalogue(":a:b", listOf(binding("one"))), fileName = "_a_b.json"),
            ),
        )

        assertEquals(1, result.bindings.size)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }
}
