package io.github.santimattius.android.hilt.trace.plugin.asm

import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.IDIV
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.VarInsnNode

private const val ID_FIELD = "id"
private const val ID_DESCRIPTOR = "I"

/** The code of one `case`: it starts at the jump table entry [label] and ends at [ret], its `ARETURN`. */
internal class CaseBody(val label: LabelNode, val ret: InsnNode)

/** What a `SwitchingProvider` method does with the `id` field. */
internal sealed interface Dispatch {

    /** The public `get()` of a split provider: it only switches on `id / 100` and calls `get0()..getN()`. */
    data object Router : Dispatch

    /** Anything the locator does not understand; the method must be left untouched. */
    data object Unknown : Dispatch

    /** A switch on `id` whose straight-line case [bodies] are listed in code layout order. */
    class Cases(val bodies: List<CaseBody>) : Dispatch
}

/**
 * Finds the case bodies of a `SwitchingProvider` method by reading the jump table of its `id`
 * switch (`tableswitch` or `lookupswitch`). Bodies are not laid out in id order, so a body is
 * matched through the label of its table entry, never by position. A body must be straight-line
 * code ending in `ARETURN`; any branch, throw or other exit makes the whole method [Dispatch.Unknown].
 */
internal fun locateCaseBodies(method: MethodNode): Dispatch {
    val switch = method.instructions.firstOrNull { it is TableSwitchInsnNode || it is LookupSwitchInsnNode } ?: return Dispatch.Unknown
    val key = switch.previousReal() ?: return Dispatch.Unknown
    if (key.opcode == IDIV) return Dispatch.Router
    if (!key.isIdRead()) return Dispatch.Unknown

    val (default, labels) = when (switch) {
        is TableSwitchInsnNode -> switch.dflt to switch.labels
        is LookupSwitchInsnNode -> switch.dflt to switch.labels
        else -> return Dispatch.Unknown
    }
    val bodies = labels.distinct().filter { it !== default }.sortedBy { method.instructions.indexOf(it) }.map { label ->
        CaseBody(label, returnOf(label) ?: return Dispatch.Unknown)
    }
    return if (bodies.isEmpty()) Dispatch.Unknown else Dispatch.Cases(bodies)
}

private fun returnOf(label: LabelNode): InsnNode? {
    var node: AbstractInsnNode? = label.next
    while (node != null) {
        when {
            node.opcode == ARETURN -> return node as InsnNode
            node is JumpInsnNode || node is TableSwitchInsnNode || node is LookupSwitchInsnNode || node.isExit() -> return null
        }
        node = node.next
    }
    return null
}

private fun AbstractInsnNode.isExit() = opcode in IRETURN..RETURN || opcode == ATHROW

private fun AbstractInsnNode.previousReal(): AbstractInsnNode? {
    var node = previous
    while (node != null && node.opcode < 0) node = node.previous
    return node
}

private fun AbstractInsnNode.isIdRead(): Boolean =
    this is FieldInsnNode && opcode == GETFIELD && name == ID_FIELD && desc == ID_DESCRIPTOR &&
        (previousReal() as? VarInsnNode)?.let { it.opcode == ALOAD && it.`var` == 0 } == true
