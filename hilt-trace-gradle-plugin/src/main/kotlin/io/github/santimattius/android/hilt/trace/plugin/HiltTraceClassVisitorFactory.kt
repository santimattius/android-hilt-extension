package io.github.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import io.github.santimattius.android.hilt.trace.plugin.asm.bufferedVisitor
import io.github.santimattius.android.hilt.trace.plugin.asm.instrumentAccessors
import io.github.santimattius.android.hilt.trace.plugin.asm.instrumentingVisitor
import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassVisitor

private val indexes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<HiltTraceClassVisitorFactory, CatalogueIndex>())

/**
 * Entry point of the bytecode instrumentation. Rule C (creation) wraps the case bodies of
 * `$SwitchingProvider` classes, rule A (accessors) the catalogued entry point methods and rule J
 * (inject) the public `inject*` methods of catalogued injection targets of the
 * `Dagger*_HiltComponents_*CImpl` classes; every other accepted class is passed through unchanged.
 * The catalogue is read once per factory: it names the slices of all three rules, and without it
 * rules A and J have nothing to wrap. `*_MembersInjector` is never instrumented (rule M is cut).
 */
public abstract class HiltTraceClassVisitorFactory : AsmClassVisitorFactory<HiltTraceParameters> {

    // Gradle serializes factory instances, so they hold no state: the index lives in a weak map keyed by instance.
    private fun index(): CatalogueIndex = indexes.getOrPut(this) { buildIndex(parameters.get().catalogues.files, ::warn) }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className
        return when {
            parameters.get().createRuleEnabled.get() && className.endsWith("\$SwitchingProvider") ->
                instrumentingVisitor(nextClassVisitor, index().names::nameOf, ::warn)
            isComponentImplementationName(className) -> {
                val index = index()
                if (!index.hasComponentRules) nextClassVisitor else bufferedVisitor(nextClassVisitor, ::warn) { instrumentAccessors(it, index::componentMethodName) }
            }
            else -> nextClassVisitor
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean = isInstrumentableClassName(classData.className)

    // Logging must never fail a class: a missing logger only drops the message.
    private fun warn(message: String) {
        runCatching { Logging.getLogger(HiltTraceClassVisitorFactory::class.java).warn(message) }
    }
}
