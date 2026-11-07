package com.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.santimattius.android.hilt.trace.plugin.asm.SliceNames
import com.santimattius.android.hilt.trace.plugin.asm.instrumentingVisitor
import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassVisitor

private val indexes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<HiltTraceClassVisitorFactory, SliceNames>())

/**
 * Entry point of the bytecode instrumentation. Rule C (creation) wraps the case bodies of
 * `$SwitchingProvider` classes; every other accepted class is passed through unchanged until the
 * later rules arrive. The catalogue is read once per factory and only enriches slice names.
 */
public abstract class HiltTraceClassVisitorFactory : AsmClassVisitorFactory<HiltTraceParameters> {

    // Gradle serializes factory instances, so they hold no state: the index lives in a weak map keyed by instance.
    private fun names(): SliceNames = indexes.getOrPut(this) {
        val merged = loadCatalogue(parameters.get().catalogues.files)
        merged.warnings.forEach(::warn)
        SliceNames(merged.bindings)
    }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        if (parameters.get().createRuleEnabled.get() && classContext.currentClassData.className.endsWith("\$SwitchingProvider")) {
            instrumentingVisitor(nextClassVisitor, names()::nameOf, ::warn)
        } else {
            nextClassVisitor
        }

    override fun isInstrumentable(classData: ClassData): Boolean = isInstrumentableClassName(classData.className)

    // Logging must never fail a class: a missing logger only drops the message.
    private fun warn(message: String) {
        runCatching { Logging.getLogger(HiltTraceClassVisitorFactory::class.java).warn(message) }
    }
}
