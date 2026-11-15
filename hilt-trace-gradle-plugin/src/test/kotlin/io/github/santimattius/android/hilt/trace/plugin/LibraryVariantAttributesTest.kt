package io.github.santimattius.android.hilt.trace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selected library variant comes from the attributes of the resolved variant, not from the display name.
 *
 * The attribute names and values are the ones observed on AGP 9.5.0 for a flavored library
 * (`BuildTypeAttr=debug`, `ProductFlavor:tier=free`, `VariantAttr=freeDebug`); a JVM library has none of them.
 */
class LibraryVariantAttributesTest {

    private val buildType = "com.android.build.api.attributes.BuildTypeAttr"
    private val flavor = "com.android.build.api.attributes.ProductFlavor:tier"
    private val variant = "com.android.build.gradle.internal.attributes.VariantAttr"

    @Test
    fun `the variant name attribute is the selected variant`() {
        val attributes = mapOf(buildType to "debug", flavor to "free", variant to "freeDebug")

        assertTrue(LibraryCataloguesTask.isAndroidLibrary(attributes))
        assertEquals("freeDebug", LibraryCataloguesTask.libraryVariant(attributes))
    }

    @Test
    fun `an unflavored library falls back to its build type`() {
        assertEquals("release", LibraryCataloguesTask.libraryVariant(mapOf(buildType to "release")))
    }

    @Test
    fun `a flavored library without the variant name attribute cannot be told`() {
        val attributes = mapOf(buildType to "debug", flavor to "free")

        assertTrue(LibraryCataloguesTask.isAndroidLibrary(attributes))
        assertNull(LibraryCataloguesTask.libraryVariant(attributes))
    }

    @Test
    fun `a JVM library has no Android attributes`() {
        val attributes = mapOf("org.gradle.usage" to "java-runtime", "org.gradle.libraryelements" to "jar")

        assertFalse(LibraryCataloguesTask.isAndroidLibrary(attributes))
        assertNull(LibraryCataloguesTask.libraryVariant(attributes))
    }
}
