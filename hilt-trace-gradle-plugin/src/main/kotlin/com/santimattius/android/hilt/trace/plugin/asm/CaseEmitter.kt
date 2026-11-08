package com.santimattius.android.hilt.trace.plugin.asm

import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/** Internal name of the runtime facade that instrumented code calls. */
internal const val FACADE = "com/santimattius/android/hilt/trace/HiltTrace"
private const val BEGIN_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;"
private const val END_DESCRIPTOR = "(Ljava/lang/Object;)V"
private const val CATEGORY_CREATE = "hilt.create"

/**
 * Wraps each case body in a `hilt.create` slice. Per case the code becomes
 * `token = begin(category, name); try { <creation> } finally { end(token) }`:
 * `end` runs before the `ARETURN` with the created value on the stack, and a catch-all handler
 * placed after the return closes the slice and rethrows. The token lives in one extra local that is
 * set to `null` on entry. A case for which [nameOf] returns `null` is left unwrapped.
 *
 * The caller must recompute frames and maxs for the method afterwards.
 */
internal fun wrapCaseBodies(method: MethodNode, bodies: List<CaseBody>, nameOf: (CaseBody) -> String?) {
    val token = method.maxLocals++
    method.instructions.insert(InsnList().apply {
        add(InsnNode(ACONST_NULL))
        add(VarInsnNode(ASTORE, token))
    })
    for (body in bodies) {
        val name = nameOf(body) ?: continue
        val (tryStart, tryEnd, handler) = Triple(LabelNode(), LabelNode(), LabelNode())
        method.instructions.insert(body.label, InsnList().apply {
            add(beginSpan(CATEGORY_CREATE, name, token))
            add(tryStart)
        })
        method.instructions.insertBefore(body.ret, InsnList().apply {
            add(tryEnd)
            add(endSpan(token))
        })
        method.instructions.insert(body.ret, InsnList().apply {
            add(handler)
            add(endSpan(token))
            add(InsnNode(ATHROW))
        })
        method.tryCatchBlocks.add(TryCatchBlockNode(tryStart, tryEnd, handler, null))
    }
}

/** `token = HiltTrace.begin(category, name)`: the call that opens a slice, shared by every rule. */
internal fun beginSpan(category: String, name: String, token: Int) = InsnList().apply {
    add(LdcInsnNode(category))
    add(LdcInsnNode(name))
    add(MethodInsnNode(INVOKESTATIC, FACADE, "begin", BEGIN_DESCRIPTOR, false))
    add(VarInsnNode(ASTORE, token))
}

/** `HiltTrace.end(token)`: the call that closes a slice; it leaves the operand stack as it found it. */
internal fun endSpan(token: Int) = InsnList().apply {
    add(VarInsnNode(ALOAD, token))
    add(MethodInsnNode(INVOKESTATIC, FACADE, "end", END_DESCRIPTOR, false))
}

/**
 * Slice name derived from the case body alone: the first constructed class (`NEW`, which for an
 * `@AssistedFactory` is the anonymous factory) or else the class of the first static call (the
 * module factory of a `@Provides`). Dotted, as Java reports class names. `null` when the body has
 * neither.
 */
internal fun fallbackName(body: CaseBody): String? =
    creationSite(body)?.let { if (it is TypeInsnNode) it.desc else (it as MethodInsnNode).owner }?.replace('/', '.')

/** The first `NEW` or `INVOKESTATIC` of the case body: the instruction that tells which binding it creates. */
internal fun creationSite(body: CaseBody): AbstractInsnNode? =
    generateSequence(body.label.next) { it.next }.takeWhile { it !== body.ret }.firstOrNull {
        (it is TypeInsnNode && it.opcode == NEW) || (it is MethodInsnNode && it.opcode == INVOKESTATIC)
    }
