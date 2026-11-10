package com.santimattius.android.hilt.trace.plugin

import com.santimattius.android.hilt.trace.plugin.asm.AccessorNames
import com.santimattius.android.hilt.trace.plugin.asm.InjectionNames
import com.santimattius.android.hilt.trace.plugin.asm.SliceNames
import java.io.File

/**
 * What the rules look up in the merged catalogue: [names] for rule C (creation), [accessors] for
 * rule A and [injections] for rule J. Rules A and J wrap methods of the same component classes, so
 * [componentMethodName] answers for both; an `@EntryPoint` accessor wins a signature both know.
 */
internal class CatalogueIndex(val names: SliceNames, val accessors: AccessorNames, val injections: InjectionNames) {

    val hasComponentRules: Boolean get() = !accessors.isEmpty || !injections.isEmpty

    fun componentMethodName(method: String, descriptor: String): String? =
        accessors.nameOf(method, descriptor) ?: injections.nameOf(method, descriptor)
}

internal const val NO_CATALOGUE_WARNING =
    "Hilt Trace: no catalogue found; slices are named from the bytecode and component accessors are not traced. " +
        "Apply KSP and the hilt-trace-compiler to the modules that declare bindings."

/**
 * Reads the catalogue roots once and builds both indexes from the merged result. Merge warnings are
 * forwarded; when the merge holds no binding, no entry point and no injection target, [NO_CATALOGUE_WARNING]
 * is reported once, here, so the rules never warn about it per class.
 */
internal fun buildIndex(roots: Collection<File>, warn: (String) -> Unit): CatalogueIndex {
    val merged = loadCatalogue(roots)
    merged.warnings.forEach(warn)
    if (merged.bindings.isEmpty() && merged.entryPoints.isEmpty() && merged.injectionTargets.isEmpty()) warn(NO_CATALOGUE_WARNING)
    return CatalogueIndex(SliceNames(merged.bindings), AccessorNames(merged.entryPoints), InjectionNames(merged.injectionTargets))
}
