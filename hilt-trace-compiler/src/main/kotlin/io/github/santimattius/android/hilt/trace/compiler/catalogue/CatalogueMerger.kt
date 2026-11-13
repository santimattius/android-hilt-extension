package io.github.santimattius.android.hilt.trace.compiler.catalogue

/**
 * A catalogue file as read by a consumer. [origin] names where it came from (a jar, a directory)
 * and is only used in warnings. [catalogue] is null when [schemaVersion] is unknown, because the
 * content of an unknown schema cannot be parsed.
 */
internal data class CatalogueInput(
    val origin: String,
    val fileName: String,
    val schemaVersion: Int,
    val catalogue: Catalogue?,
)

internal data class MergeResult(
    val bindings: List<Binding>,
    val entryPoints: List<EntryPoint>,
    val injectionTargets: List<InjectionTarget>,
    val warnings: List<String>,
)

/**
 * Pure merge of catalogue files. Identical entries are deduplicated, the first entry wins when two
 * entries with the same identity differ (with a warning), and unknown schema versions are skipped
 * with a warning. This file has no dependency on KSP so the Gradle plugin can reuse it.
 */
internal object CatalogueMerger {

    fun merge(inputs: List<CatalogueInput>): MergeResult {
        val warnings = mutableListOf<String>()
        val usable = inputs.mapNotNull { input ->
            val catalogue = input.catalogue
            if (input.schemaVersion == SCHEMA_VERSION && catalogue != null) {
                input to catalogue
            } else {
                warnings += "Skipping catalogue ${input.origin}: unsupported schemaVersion ${input.schemaVersion}"
                null
            }
        }
        warnOnFileNameCollisions(usable, warnings)

        return MergeResult(
            bindings = firstWins(usable, warnings, { it.bindings }, { Triple(it.kind, it.owner, it.method) }, { "binding ${it.owner}.${it.method}" }),
            entryPoints = firstWins(usable, warnings, { it.entryPoints }, { Triple(it.interfaceName, it.method, it.descriptor) }, { "entry point ${it.interfaceName}.${it.method}" }),
            injectionTargets = firstWins(usable, warnings, { it.injectionTargets }, { it.type }, { "injection target ${it.type}" }),
            warnings = warnings,
        )
    }

    private fun warnOnFileNameCollisions(usable: List<Pair<CatalogueInput, Catalogue>>, warnings: MutableList<String>) {
        usable.groupBy({ it.first.fileName }, { it.second.module }).forEach { (fileName, modules) ->
            val distinct = modules.distinct()
            if (distinct.size > 1) {
                warnings += "File name $fileName is used by conflicting module ids ${distinct.joinToString()}; " +
                    "ids that differ only by ':' versus '_' share one file name"
            }
        }
    }

    private fun <T, K> firstWins(
        usable: List<Pair<CatalogueInput, Catalogue>>,
        warnings: MutableList<String>,
        select: (Catalogue) -> List<T>,
        identity: (T) -> K,
        label: (T) -> String,
    ): List<T> {
        val kept = LinkedHashMap<K, T>()
        for ((input, catalogue) in usable) {
            for (entry in select(catalogue)) {
                val first = kept.putIfAbsent(identity(entry), entry)
                if (first != null && first != entry) {
                    warnings += "Conflicting ${label(entry)} in ${input.origin}; keeping the first one"
                }
            }
        }
        return kept.values.toList()
    }
}
