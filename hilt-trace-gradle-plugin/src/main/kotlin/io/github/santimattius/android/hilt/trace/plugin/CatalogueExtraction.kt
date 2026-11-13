package io.github.santimattius.android.hilt.trace.plugin

import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_DIRECTORY
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_EXTENSION
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies the catalogue files of one classpath entry into [outputRoot], keeping the
 * `META-INF/hilt-trace/<name>.json` layout. [input] is either a jar (an AAR `classes.jar` or a
 * library jar) or a classes directory (a project dependency).
 *
 * Fail open: an unreadable input never fails the build. It contributes nothing and the returned
 * message tells the caller to warn once. Returns null when the input was read.
 */
internal fun extractCatalogues(input: File, outputRoot: File): String? {
    outputRoot.mkdirs()
    return try {
        if (input.isDirectory) {
            input.resolve(CATALOGUE_DIRECTORY).listFiles { file -> isCatalogueName(file.name) }.orEmpty()
                .forEach { copy(it.inputStream(), outputRoot, it.name) }
        } else if (input.isFile) {
            ZipFile(input).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name.removePrefix("$CATALOGUE_DIRECTORY/")
                    if (!entry.isDirectory && name != entry.name && isCatalogueName(name)) {
                        copy(zip.getInputStream(entry), outputRoot, name)
                    }
                }
            }
        }
        null
    } catch (e: Exception) {
        "Skipping catalogues of ${input.name}: unreadable (${e.javaClass.simpleName})"
    }
}

/** A flat `*.json` name; a name with a separator would let an entry escape the output directory. */
private fun isCatalogueName(name: String) = '/' !in name && name.endsWith(".$CATALOGUE_EXTENSION")

private fun copy(source: java.io.InputStream, outputRoot: File, name: String) {
    val target = outputRoot.resolve("$CATALOGUE_DIRECTORY/$name").also { it.parentFile.mkdirs() }
    source.use { stream -> target.outputStream().use { stream.copyTo(it) } }
}
