package com.santimattius.android.hilt.trace.plugin.asm

import com.santimattius.android.hilt.trace.compiler.catalogue.Binding
import com.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Joins case bodies with the merged catalogue to name a slice `Type[@Qualifier]`, the same format as
 * the runtime facade's `sliceName`. A constructor binding (`INJECT`, `ASSISTED_INJECT`) is found by
 * the class its case constructs; a `PROVIDES` binding by the module factory its case calls
 * (`<Module>_<Method>Factory`). Scope is not part of the name. Without an entry the name falls back
 * to [fallbackName], so a missing catalogue never prevents a slice.
 */
internal class SliceNames(bindings: List<Binding>) {

    private val constructors = bindings.filter { it.kind == BindingKind.INJECT || it.kind == BindingKind.ASSISTED_INJECT }
        .associateBy { it.owner }
    private val factories = bindings.filter { it.kind == BindingKind.PROVIDES }
        .associateBy { flat("${it.owner}_${it.method.replaceFirstChar(Char::uppercase)}Factory") }

    fun nameOf(body: CaseBody): String? {
        val binding = when (val site = creationSite(body)) {
            is TypeInsnNode -> constructors[site.desc.replace('/', '.').replace('$', '.')]
            is MethodInsnNode -> factories[flat(site.owner)]
            else -> null
        }
        return when {
            binding == null -> fallbackName(body)
            binding.qualifier.isNullOrEmpty() -> binding.type
            else -> "${binding.type}@${binding.qualifier}"
        }
    }

    /** Package separators, nesting separators and underscores compare equal, since only the flat name is known. */
    private fun flat(name: String) = name.replace('.', '_').replace('/', '_').replace('$', '_')
}
