package com.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

/**
 * Entry point of the bytecode instrumentation. The instrumentation rules arrive in later slices,
 * so for now every accepted class is passed through unchanged.
 */
public abstract class HiltTraceClassVisitorFactory : AsmClassVisitorFactory<HiltTraceParameters> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        nextClassVisitor

    override fun isInstrumentable(classData: ClassData): Boolean = isInstrumentableClassName(classData.className)
}
