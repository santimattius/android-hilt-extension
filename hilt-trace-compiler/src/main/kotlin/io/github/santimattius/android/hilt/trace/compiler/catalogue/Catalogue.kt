package io.github.santimattius.android.hilt.trace.compiler.catalogue

/** Schema version written by the processor; the only version the merger understands. */
internal const val SCHEMA_VERSION = 1

/** Jar resource directory that holds the per-module catalogue files. */
internal const val CATALOGUE_DIRECTORY = "META-INF/hilt-trace"

/** Extension of a catalogue file, without the dot. */
internal const val CATALOGUE_EXTENSION = "json"

internal enum class BindingKind { PROVIDES, BINDS, INJECT, ASSISTED_INJECT }

/** A binding declaration. [owner] and [method] identify it; constructors use the method `<init>`. */
internal data class Binding(
    val kind: BindingKind,
    val type: String,
    val qualifier: String?,
    val scope: String?,
    val owner: String,
    val method: String,
)

/** An `@EntryPoint` interface method with its JVM [descriptor], for example `()Lcom/x/Foo;`. */
internal data class EntryPoint(
    val interfaceName: String,
    val method: String,
    val descriptor: String,
    val component: String,
)

/** A class that receives injection, for example an `@AndroidEntryPoint` class. */
internal data class InjectionTarget(val type: String)

/** The content of one module catalogue file. [module] is the original module id, never sanitized. */
internal data class Catalogue(
    val module: String,
    val bindings: List<Binding>,
    val entryPoints: List<EntryPoint>,
    val injectionTargets: List<InjectionTarget>,
)

/**
 * File name stem for a module id: every `:` becomes `_` because a colon is not a valid jar entry
 * name character. The JSON `module` field keeps the original id.
 *
 * Ids that differ only by `:` versus `_` (for example `:a:b` and `:a_b`) map to the same stem. Both
 * modules still emit their catalogue, but if both end up in one app, the packaging step keeps only
 * one resource for the duplicated path. [CatalogueMerger] warns when files with one name carry
 * conflicting `module` fields. No hash suffix is added, so the file name stays predictable.
 */
internal fun catalogueFileStem(moduleId: String): String = moduleId.replace(':', '_')
