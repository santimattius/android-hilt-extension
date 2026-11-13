package io.github.santimattius.android.hilt.trace.plugin

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogueExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jar(vararg entries: Pair<String, String>): File = tmp.newFile("lib.jar").also { file ->
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun relativeFiles(root: File) =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `jar form yields only the catalogue files`() {
        val out = tmp.newFolder("out")
        val input = jar(
            "META-INF/hilt-trace/_a.json" to """{"module": ":a"}""",
            "META-INF/hilt-trace/_b.json" to """{"module": ":b"}""",
            "META-INF/other/c.json" to "{}",
            "com/x/Foo.class" to "bytes",
        )

        assertNull(extractCatalogues(input, out))

        assertEquals(listOf("META-INF/hilt-trace/_a.json", "META-INF/hilt-trace/_b.json"), relativeFiles(out))
        assertEquals("""{"module": ":b"}""", out.resolve("META-INF/hilt-trace/_b.json").readText())
    }

    @Test
    fun `directory form yields only the catalogue files`() {
        val out = tmp.newFolder("out")
        val dir = tmp.newFolder("classes")
        dir.resolve("META-INF/hilt-trace").mkdirs()
        dir.resolve("META-INF/hilt-trace/_d.json").writeText("""{"module": ":d"}""")
        dir.resolve("META-INF/hilt-trace/notes.txt").writeText("not a catalogue")
        dir.resolve("com/x").mkdirs()
        dir.resolve("com/x/Foo.class").writeText("bytes")

        assertNull(extractCatalogues(dir, out))

        assertEquals(listOf("META-INF/hilt-trace/_d.json"), relativeFiles(out))
    }

    @Test
    fun `an input without catalogues yields no files and no warning`() {
        val out = tmp.newFolder("out")

        assertNull(extractCatalogues(jar("com/x/Foo.class" to "bytes"), out))

        assertTrue(relativeFiles(out).isEmpty())
    }

    @Test
    fun `an unreadable input is skipped with one warning instead of failing`() {
        val out = tmp.newFolder("out")
        val broken = tmp.newFile("broken.jar").also { it.writeText("this is not a zip archive") }

        val warning = extractCatalogues(broken, out)

        assertNotNull(warning)
        assertTrue(warning, "broken.jar" in warning!!)
        assertTrue(relativeFiles(out).isEmpty())
    }
}
