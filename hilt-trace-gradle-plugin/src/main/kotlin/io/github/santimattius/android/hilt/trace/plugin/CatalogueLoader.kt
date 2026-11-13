package io.github.santimattius.android.hilt.trace.plugin

import groovy.json.JsonSlurper
import io.github.santimattius.android.hilt.trace.compiler.catalogue.Binding
import io.github.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_DIRECTORY
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_EXTENSION
import io.github.santimattius.android.hilt.trace.compiler.catalogue.Catalogue
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CatalogueInput
import io.github.santimattius.android.hilt.trace.compiler.catalogue.CatalogueMerger
import io.github.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import io.github.santimattius.android.hilt.trace.compiler.catalogue.MergeResult
import io.github.santimattius.android.hilt.trace.compiler.catalogue.SCHEMA_VERSION
import java.io.File

/**
 * Reads every `META-INF/hilt-trace/<name>.json` under the given [roots] (transform outputs or the app's
 * own KSP resources directory) and merges them with [CatalogueMerger].
 *
 * Fail open: a missing root contributes nothing, and an unreadable file or an unknown schema is
 * skipped with one warning in [MergeResult.warnings]. The build never fails because of a catalogue.
 */
internal fun loadCatalogue(roots: Collection<File>): MergeResult {
    val readWarnings = mutableListOf<String>()
    val inputs = roots.flatMap { root ->
        root.resolve(CATALOGUE_DIRECTORY).listFiles { file -> file.extension == CATALOGUE_EXTENSION }.orEmpty().sortedBy { it.name }
    }.mapNotNull { file ->
        try {
            parse(file)
        } catch (e: Exception) {
            readWarnings += "Skipping catalogue ${file.path}: unreadable (${e.javaClass.simpleName})"
            null
        }
    }
    val merged = CatalogueMerger.merge(inputs)
    return merged.copy(warnings = readWarnings + merged.warnings)
}

private fun parse(file: File): CatalogueInput {
    val json = JsonSlurper().parse(file) as Map<*, *>
    val version = (json["schemaVersion"] as Number).toInt()
    // The content of an unknown schema is not parsed; the merger reports it as skipped.
    return CatalogueInput(file.path, file.name, version, if (version == SCHEMA_VERSION) catalogue(json) else null)
}

private fun catalogue(json: Map<*, *>) = Catalogue(
    module = json["module"] as String,
    bindings = objects(json, "bindings").map {
        Binding(
            BindingKind.valueOf(it["kind"] as String),
            it["type"] as String,
            it["qualifier"] as String?,
            it["scope"] as String?,
            it["owner"] as String,
            it["method"] as String,
        )
    },
    entryPoints = objects(json, "entryPoints").map {
        EntryPoint(it["interface"] as String, it["method"] as String, it["descriptor"] as String, it["component"] as String)
    },
    injectionTargets = objects(json, "injectionTargets").map { InjectionTarget(it["type"] as String) },
)

private fun objects(json: Map<*, *>, key: String): List<Map<*, *>> = (json[key] as List<*>).map { it as Map<*, *> }
