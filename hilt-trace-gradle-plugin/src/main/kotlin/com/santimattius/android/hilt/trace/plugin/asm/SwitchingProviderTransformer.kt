package com.santimattius.android.hilt.trace.plugin.asm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

private const val SWITCHING_PROVIDER_SUFFIX = "\$SwitchingProvider"
private const val PROVIDER_METHOD_DESCRIPTOR = "()Ljava/lang/Object;"
private val PROVIDER_METHOD_NAME = Regex("get\\d*")

/** Largest size, in bytes, accepted for a scratch class holding one instrumented method (JVM limit is 64 KB). */
internal const val MAX_METHOD_BYTES = 63 * 1024

/** The result of [instrumentSwitchingProvider]: [instrumented] is true only when the node was changed. */
internal class Outcome(val instrumented: Boolean, val warning: String? = null)

/** The result of [transformSwitchingProvider]: [bytes] is the very same array as the input unless the class was instrumented. */
internal class TransformResult(val bytes: ByteArray, val warning: String?)

/**
 * Wraps the case bodies of the `get`/`getN` methods of a `$SwitchingProvider` node. Fail open: the
 * node is changed only after every method succeeded, so any problem (unknown shape, a method that
 * would exceed the code limit, any throwable) leaves it exactly as read and reports one warning.
 * Frames and maxs are computed for the instrumented methods only, through a scratch [ClassWriter].
 * A class that is not a `$SwitchingProvider` is passed through silently.
 */
internal fun instrumentSwitchingProvider(
    node: ClassNode,
    nameOf: (CaseBody) -> String?,
    codeLimit: Int = MAX_METHOD_BYTES,
): Outcome {
    if (!node.name.endsWith(SWITCHING_PROVIDER_SUFFIX)) return Outcome(false)
    fun unchanged(reason: String) = Outcome(false, "Hilt Trace: ${node.name} left unchanged: $reason")
    return try {
        val replacements = mutableMapOf<MethodNode, MethodNode>()
        for (method in node.methods.filter { it.desc == PROVIDER_METHOD_DESCRIPTOR && PROVIDER_METHOD_NAME.matches(it.name) }) {
            when (locateCaseBodies(method)) {
                is Dispatch.Router -> Unit
                is Dispatch.Unknown -> return unchanged("unrecognized shape in ${method.name}")
                is Dispatch.Cases -> {
                    val copy = MethodNode(ASM9, method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray())
                    method.accept(copy)
                    wrapCaseBodies(copy, (locateCaseBodies(copy) as Dispatch.Cases).bodies, nameOf)
                    val scratch = scratchFrames(node, copy, codeLimit) ?: return unchanged("${method.name} exceeds the $codeLimit byte limit")
                    replacements[method] = scratch
                }
            }
        }
        if (replacements.isEmpty()) return unchanged("no case bodies found")
        replacements.forEach { (old, new) -> node.methods[node.methods.indexOf(old)] = new }
        Outcome(true)
    } catch (e: Throwable) {
        unchanged("${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * The streaming form AGP needs: buffers the class in a [ClassNode], instruments it and replays it
 * into [next]. When instrumentation does not apply the node is replayed exactly as read, and the
 * warning, if any, goes to [warn] once.
 */
internal fun instrumentingVisitor(next: ClassVisitor, nameOf: (CaseBody) -> String?, warn: (String) -> Unit): ClassVisitor =
    object : ClassNode(ASM9) {
        override fun visitEnd() {
            super.visitEnd()
            instrumentSwitchingProvider(this, nameOf).warning?.let(warn)
            accept(next)
        }
    }

/** Reads [original], instruments it and writes it back; every non-instrumented outcome returns [original] itself. */
internal fun transformSwitchingProvider(
    original: ByteArray,
    nameOf: (CaseBody) -> String?,
    codeLimit: Int = MAX_METHOD_BYTES,
): TransformResult = try {
    val node = ClassNode().also { ClassReader(original).accept(it, 0) }
    val outcome = instrumentSwitchingProvider(node, nameOf, codeLimit)
    if (outcome.instrumented) TransformResult(ClassWriter(0).also { node.accept(it) }.toByteArray(), null)
    else TransformResult(original, outcome.warning)
} catch (e: Throwable) {
    TransformResult(original, "Hilt Trace: class left unchanged: ${e.javaClass.simpleName}: ${e.message}")
}

/**
 * Writes [method] alone into a scratch class so ASM computes its frames and maxs, then reads it back.
 * Returns null when the scratch class is larger than the limit; a method above 64 KB throws.
 */
private fun scratchFrames(owner: ClassNode, method: MethodNode, codeLimit: Int): MethodNode? {
    val writer = framesWriter()
    writer.visit(owner.version, owner.access, owner.name, owner.signature, owner.superName, owner.interfaces.toTypedArray())
    method.accept(writer)
    writer.visitEnd()
    val bytes = writer.toByteArray()
    if (bytes.size > codeLimit) return null
    return ClassNode().also { ClassReader(bytes).accept(it, 0) }.methods.single { it.name == method.name && it.desc == method.desc }
}
