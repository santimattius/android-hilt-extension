package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.plugin.isAssistedFactoryName
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode

private const val CREATE = "create"

/**
 * Rule C for assisted creation. Hilt builds the factory of an `@AssistedInject` class (and of a
 * `@HiltWorker`) as an anonymous `$SwitchingProvider$N` class: it has no jump table, its public
 * `create(<assisted args>)` is straight-line code that constructs the class. The whole method is one
 * case body, so it is wrapped like a case: `hilt.create` around the construction, with the creation
 * of the dependencies nested inside. A class that is not such a factory is passed through silently;
 * fail open exactly like [instrumentSwitchingProvider]: a `create` that is not straight-line code, a
 * method over the code limit or any throwable leaves the node as read and reports one warning.
 */
internal fun instrumentAssistedFactory(
    node: ClassNode,
    nameOf: (CaseBody) -> String?,
    codeLimit: Int = MAX_METHOD_BYTES,
): Outcome {
    if (!isAssistedFactoryName(node.name)) return Outcome(false)
    fun unchanged(reason: String) = Outcome(false, "Hilt Trace: ${node.name} left unchanged: $reason")
    return try {
        val replacements = mutableMapOf<MethodNode, MethodNode>()
        for (method in node.methods.filter { it.isFactoryMethod() }) {
            val copy = copyOf(method)
            val ret = straightLineReturn(copy) ?: return unchanged("unrecognized shape in ${method.name}")
            val body = CaseBody(LabelNode().also { copy.instructions.insert(it) }, ret)
            if (nameOf(body) == null) continue
            wrapCaseBodies(copy, listOf(body), nameOf)
            val scratch = scratchFrames(node, copy, codeLimit) ?: return unchanged("${method.name} exceeds the $codeLimit byte limit")
            replacements[method] = scratch
        }
        if (replacements.isEmpty()) return unchanged("no create method wrapped")
        replacements.forEach { (old, new) -> node.methods[node.methods.indexOf(old)] = new }
        Outcome(true)
    } catch (e: Throwable) {
        unchanged("${e.javaClass.simpleName}: ${e.message}")
    }
}

/** The byte array form of [instrumentAssistedFactory]: the very same array comes back unless the class was instrumented. */
internal fun transformAssistedFactory(
    original: ByteArray,
    nameOf: (CaseBody) -> String?,
    codeLimit: Int = MAX_METHOD_BYTES,
): TransformResult = transformClass(original) { instrumentAssistedFactory(it, nameOf, codeLimit) }

/** The visitor AGP needs: [instrumentAssistedFactory] over the buffered class. */
internal fun assistedFactoryVisitor(next: ClassVisitor, nameOf: (CaseBody) -> String?, warn: (String) -> Unit): ClassVisitor =
    bufferedVisitor(next, warn) { instrumentAssistedFactory(it, nameOf) }

/** The public `create` that implements the factory interface; the compiler's bridge to an erased signature is skipped. */
private fun MethodNode.isFactoryMethod() =
    name == CREATE && access and (ACC_STATIC or ACC_ABSTRACT or ACC_SYNTHETIC or ACC_BRIDGE) == 0

/**
 * The only `ARETURN` of [method] when the method is straight-line code that ends in it: no branch,
 * switch, throw, exception handler or other exit. `null` for any other shape.
 */
private fun straightLineReturn(method: MethodNode): InsnNode? {
    if (method.tryCatchBlocks.isNotEmpty()) return null
    val real = method.instructions.filter { it.opcode >= 0 }
    val last = real.lastOrNull() ?: return null
    if (last.opcode != ARETURN) return null
    if (real.dropLast(1).any { it.isControlFlow() }) return null
    return last as InsnNode
}

private fun AbstractInsnNode.isControlFlow() =
    this is JumpInsnNode || this is TableSwitchInsnNode || this is LookupSwitchInsnNode || opcode == ATHROW || opcode in IRETURN..RETURN
