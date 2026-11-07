package com.santimattius.android.hilt.trace.plugin.asm

import com.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import com.santimattius.android.hilt.trace.plugin.isComponentImplementationName
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode

private const val CATEGORY_INJECT = "hilt.inject"
private const val ACCESSOR_MASK = ACC_PUBLIC or ACC_STATIC or ACC_ABSTRACT or ACC_SYNTHETIC or ACC_BRIDGE

/**
 * The catalogue's `@EntryPoint` methods, looked up by method name and JVM descriptor. A hit names
 * the slice `Component.method`, with `Component` the simple name of the component the interface is
 * installed in (the first one when several), or of the interface itself when it has none. When two
 * entries share a name and descriptor the first one wins. An empty index means rule A does nothing.
 */
internal class AccessorNames(entryPoints: List<EntryPoint>) {

    private val names = HashMap<Pair<String, String>, String>().also { map ->
        entryPoints.forEach { map.putIfAbsent(it.method to it.descriptor, "${ownerName(it)}.${it.method}") }
    }

    val isEmpty: Boolean get() = names.isEmpty()

    fun nameOf(method: String, descriptor: String): String? = names[method to descriptor]

    private fun ownerName(entry: EntryPoint) =
        entry.component.substringBefore(',').substringAfterLast('.').ifEmpty { entry.interfaceName.substringAfterLast('.') }
}

/**
 * Rule A: wraps whole public instance methods of a component implementation class in a
 * `hilt.inject` slice, but only those [nameOf] knows by name and descriptor. Everything else (private
 * helpers, uncatalogued methods, other classes) is left alone, and an inline construction inside an
 * accessor gets no slice of its own.
 *
 * Fail open, as for rule C: the node changes only after every accessor succeeded, and a shape that
 * is not straight-line code, a method over the code limit or any throwable leaves it as read with
 * one warning. Straight-line is required because the scratch writer cannot merge two different
 * reference types at a join point.
 */
internal fun instrumentAccessors(node: ClassNode, nameOf: (String, String) -> String?, codeLimit: Int = MAX_METHOD_BYTES): Outcome {
    if (!isComponentImplementationName(node.name.replace('/', '.'))) return Outcome(false)
    fun unchanged(reason: String) = Outcome(false, "Hilt Trace: ${node.name} left unchanged: $reason")
    return try {
        val replacements = mutableMapOf<MethodNode, MethodNode>()
        for (method in node.methods.filter { it.access and ACCESSOR_MASK == ACC_PUBLIC }) {
            val name = nameOf(method.name, method.desc) ?: continue
            if (!isStraightLine(method)) return unchanged("unrecognized shape in ${method.name}")
            val copy = copyOf(method).also { wrapWholeMethod(it, CATEGORY_INJECT, name) }
            replacements[method] = scratchFrames(node, copy, codeLimit) ?: return unchanged("${method.name} exceeds the $codeLimit byte limit")
        }
        if (replacements.isEmpty()) return Outcome(false)
        replacements.forEach { (old, new) -> node.methods[node.methods.indexOf(old)] = new }
        Outcome(true)
    } catch (e: Throwable) {
        unchanged("${e.javaClass.simpleName}: ${e.message}")
    }
}

private fun isStraightLine(method: MethodNode) = method.tryCatchBlocks.isEmpty() &&
    method.instructions.none { it is JumpInsnNode || it is TableSwitchInsnNode || it is LookupSwitchInsnNode }

/**
 * `token = begin(category, name); try { <body> } finally { end(token) }` for a straight-line
 * method: `end` runs before the return with the result on the stack, and a catch-all handler after
 * it closes the slice and rethrows (omitted when the body has no instruction to protect). The
 * caller must recompute frames and maxs afterwards.
 */
internal fun wrapWholeMethod(method: MethodNode, category: String, name: String) {
    val token = method.maxLocals++
    val (tryStart, tryEnd, handler) = Triple(LabelNode(), LabelNode(), LabelNode())
    method.instructions.insert(InsnList().apply {
        add(beginSpan(category, name, token))
        add(tryStart)
    })
    val ret = method.instructions.firstOrNull { it.opcode in IRETURN..RETURN }
    if (ret == null) method.instructions.add(tryEnd) else method.instructions.insertBefore(ret, InsnList().apply {
        add(tryEnd)
        add(endSpan(token))
    })
    // An empty body (a lone return) protects no instruction, and an empty exception range is not a valid class file.
    if (generateSequence(tryStart.next) { it.next }.takeWhile { it !== tryEnd }.none { it.opcode >= 0 }) return
    method.instructions.add(InsnList().apply {
        add(handler)
        add(endSpan(token))
        add(InsnNode(ATHROW))
    })
    method.tryCatchBlocks.add(TryCatchBlockNode(tryStart, tryEnd, handler, null))
}
