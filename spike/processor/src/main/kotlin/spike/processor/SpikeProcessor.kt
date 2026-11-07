package spike.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/** Writes META-INF/hilt-trace/<module>.json once, in finish(), even when no symbol was seen. */
class SpikeProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        SpikeProcessor(environment)
}

private class SpikeProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var classes = 0

    override fun process(resolver: Resolver): List<KSAnnotated> {
        classes += resolver.getNewFiles().flatMap { it.declarations }.filterIsInstance<KSClassDeclaration>().count()
        return emptyList()
    }

    override fun finish() {
        val module = environment.options["hiltTrace.module"].orEmpty().trimStart(':').replace(':', '_').ifEmpty { "unnamed" }
        environment.codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "META-INF/hilt-trace/$module", "json")
            .bufferedWriter().use { it.write("""{"schemaVersion":1,"module":"$module","classes":$classes}""") }
    }
}
