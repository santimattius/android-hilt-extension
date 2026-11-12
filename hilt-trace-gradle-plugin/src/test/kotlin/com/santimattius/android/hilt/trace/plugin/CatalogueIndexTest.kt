package com.santimattius.android.hilt.trace.plugin

import com.santimattius.android.hilt.trace.compiler.catalogue.Binding
import com.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import com.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import com.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import com.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import com.santimattius.android.hilt.trace.compiler.catalogue.JsonWriter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The index the factory builds once per build: slice names for rule C, accessor names for rule A. */
class CatalogueIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()

    private val binding = Binding(BindingKind.INJECT, "p.Foo", null, null, "p.Foo", "<init>")
    private val entryPoint = EntryPoint("p.Exposed", "foo", "()Lp/Foo;", "dagger.hilt.components.SingletonComponent")

    private fun root(json: String): File = tmp.newFolder().also {
        it.resolve("META-INF/hilt-trace").mkdirs()
        it.resolve("META-INF/hilt-trace/app.json").writeText(json)
    }

    private fun index(vararg roots: File) = buildIndex(roots.toList(), warnings::add)

    private fun catalogue(bindings: List<Binding> = emptyList(), entryPoints: List<EntryPoint> = emptyList(), targets: List<InjectionTarget> = emptyList()) =
        JsonWriter.write(Catalogue(":app", bindings, entryPoints, targets))

    private fun noCatalogueWarnings() = warnings.filter { "no catalogue found" in it }

    @Test
    fun `entry points give the accessor names and no warning`() {
        val index = index(root(catalogue(entryPoints = listOf(entryPoint))))

        assertEquals("SingletonComponent.foo", index.accessors.nameOf("foo", "()Lp/Foo;"))
        assertNull(index.accessors.nameOf("foo", "()Lp/Bar;"))
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test
    fun `bindings only is a catalogue so rule A has nothing to wrap and nothing is warned`() {
        val index = index(root(catalogue(bindings = listOf(binding))))

        assertTrue(index.accessors.isEmpty)
        assertTrue(index.injections.isEmpty)
        assertFalse(index.hasComponentRules)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test
    fun `an injection target alone is a catalogue too and names the inject method`() {
        val index = index(root(catalogue(targets = listOf(InjectionTarget("p.MainActivity")))))

        assertEquals("MainActivity.members", index.injections.nameOf("injectMainActivity", "(Lp/MainActivity;)V"))
        assertTrue(index.accessors.isEmpty)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test
    fun `one lookup serves rule A and rule J and rule A wins a shared signature`() {
        val shared = EntryPoint("p.Exposed", "injectMainActivity", "(Lp/MainActivity;)V", "dagger.hilt.components.SingletonComponent")
        val index = index(root(catalogue(entryPoints = listOf(entryPoint, shared), targets = listOf(InjectionTarget("p.MainActivity")))))

        assertEquals("SingletonComponent.foo", index.componentMethodName("foo", "()Lp/Foo;"))
        assertEquals("SingletonComponent.injectMainActivity", index.componentMethodName("injectMainActivity", "(Lp/MainActivity;)V"))
        assertNull(index.componentMethodName("injectOther", "(Lp/Other;)V"))
        assertTrue(index.hasComponentRules)
    }

    @Test
    fun `no catalogue at all warns exactly once and rule A is off`() {
        val index = index(File(tmp.root, "absent"), tmp.newFolder("empty"))

        assertTrue(index.accessors.isEmpty)
        assertEquals(warnings.toString(), 1, warnings.size)
        assertEquals(warnings.toString(), 1, noCatalogueWarnings().size)
    }

    @Test
    fun `an unknown schema is skipped with its own warning and the build still warns once about the missing catalogue`() {
        val future = catalogue(entryPoints = listOf(entryPoint)).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val index = index(root(future))

        assertTrue(index.accessors.isEmpty)
        assertEquals(warnings.toString(), 2, warnings.size)
        assertEquals(warnings.toString(), 1, warnings.count { "schemaVersion 2" in it })
        assertEquals(warnings.toString(), 1, noCatalogueWarnings().size)
    }

    @Test
    fun `an unknown schema next to a readable catalogue keeps the readable one and does not claim a missing catalogue`() {
        val future = catalogue().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val index = index(root(future), root(catalogue(entryPoints = listOf(entryPoint))))

        assertEquals("SingletonComponent.foo", index.accessors.nameOf("foo", "()Lp/Foo;"))
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(noCatalogueWarnings().isEmpty())
    }
}
