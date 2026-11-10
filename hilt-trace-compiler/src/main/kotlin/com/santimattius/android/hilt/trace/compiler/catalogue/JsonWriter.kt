package com.santimattius.android.hilt.trace.compiler.catalogue

/** Hand-written, deterministic JSON writer for schema version 1: one entry per line. */
internal object JsonWriter {

    fun write(catalogue: Catalogue): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $SCHEMA_VERSION,")
        appendLine("  \"module\": ${quote(catalogue.module)},")
        section("bindings", catalogue.bindings.map(::bindingJson), trailingComma = true)
        section("entryPoints", catalogue.entryPoints.map(::entryPointJson), trailingComma = true)
        section("injectionTargets", catalogue.injectionTargets.map { obj("type" to it.type) }, trailingComma = false)
        appendLine("}")
    }

    private fun StringBuilder.section(name: String, entries: List<String>, trailingComma: Boolean) {
        val comma = if (trailingComma) "," else ""
        if (entries.isEmpty()) {
            appendLine("  \"$name\": []$comma")
            return
        }
        appendLine("  \"$name\": [")
        entries.forEachIndexed { index, entry ->
            appendLine("    $entry${if (index < entries.lastIndex) "," else ""}")
        }
        appendLine("  ]$comma")
    }

    private fun bindingJson(binding: Binding) = obj(
        "kind" to binding.kind.name,
        "type" to binding.type,
        "qualifier" to binding.qualifier,
        "scope" to binding.scope,
        "owner" to binding.owner,
        "method" to binding.method,
    )

    private fun entryPointJson(entryPoint: EntryPoint) = obj(
        "interface" to entryPoint.interfaceName,
        "method" to entryPoint.method,
        "descriptor" to entryPoint.descriptor,
        "component" to entryPoint.component,
    )

    private fun obj(vararg fields: Pair<String, String?>): String =
        fields.joinToString(", ", "{", "}") { (key, value) -> "\"$key\": ${value?.let(::quote) ?: "null"}" }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
