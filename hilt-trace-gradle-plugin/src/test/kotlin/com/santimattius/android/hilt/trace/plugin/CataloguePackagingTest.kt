package com.santimattius.android.hilt.trace.plugin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TestKit builds that list what the APK of a variant really packages. The catalogue lists binding
 * types, qualifiers and graph structure, so only a variant the plugin instruments may carry it.
 */
class CataloguePackagingTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val bothCatalogues = listOf("META-INF/hilt-trace/app.json", "META-INF/hilt-trace/lib.json")

    @Test
    fun `release packages no catalogue while debug keeps both and is instrumented`() {
        val app = ThrowawayApp(projectDir.root, withPlugin = true)
        app.build("assembleDebug", "assembleRelease")

        assertEquals(emptyList<String>(), app.catalogueEntries("release"))
        assertEquals(bothCatalogues, app.catalogueEntries("debug"))
        // Other java resources are not affected by the exclusion.
        assertEquals(listOf("fixture/keep.txt"), app.apkEntries("release").filter { it == "fixture/keep.txt" })
        assertEquals(listOf("begin", "end", "end"), app.debugFacadeCalls("consumer"))
    }

    @Test
    fun `the rule follows the selection, not the variant name`() {
        val app = ThrowawayApp(projectDir.root, withPlugin = true, "hiltTrace { variants.set(listOf(\"release\")) }")
        app.build("assembleDebug", "assembleRelease")

        assertEquals(bothCatalogues, app.catalogueEntries("release"))
        assertEquals(emptyList<String>(), app.catalogueEntries("debug"))
    }

    @Test
    fun `excluding the catalogue from packaging does not hide it from the transform`() {
        val app = ThrowawayApp(projectDir.root, withPlugin = true, "android.packaging.resources.excludes += \"META-INF/hilt-trace/**\"")
        app.build("assembleDebug")

        assertEquals(emptyList<String>(), app.catalogueEntries("debug"))
        assertEquals(listOf("begin", "end", "end"), app.debugFacadeCalls("consumer"))
    }
}
