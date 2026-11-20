package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.plugin.isMembersInjectorName
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

private const val INJECT_MEMBERS = "injectMembers"
private const val CATEGORY_INJECT = "hilt.inject"
private const val METHOD_MASK = ACC_PUBLIC or ACC_STATIC or ACC_ABSTRACT or ACC_SYNTHETIC or ACC_BRIDGE
private val TARGET_DESCRIPTOR = Regex("\\(L([^;\\[(]+);\\)V")

/**
 * Opt-in rule for `MembersInjector.injectMembers`. Hilt generates `Target_MembersInjector` with a real
 * instance method `injectMembers(Target)` that the component never calls (it calls the static
 * setters); only a consumer that receives a `MembersInjector<Target>` through DI calls it. The method
 * is wrapped whole in a `hilt.inject` slice named `Target.injectMembers`, `Target` the simple name of
 * the parameter type; the suffix keeps it apart from the `Target.members` slice of the inject rule.
 * The erased bridge `injectMembers(Object)` calls the typed method, so it is left alone (one slice
 * per call). A class that does not end in `_MembersInjector` is passed through silently; fail open
 * exactly like the other rules: a shape that is not straight-line code, a method over the code limit,
 * a class with no matching method or any throwable leaves the node as read and reports one warning.
 */
internal fun instrumentMembersInjector(node: ClassNode, codeLimit: Int = MAX_METHOD_BYTES): Outcome {
    if (!isMembersInjectorName(node.name)) return Outcome(false)
    fun unchanged(reason: String) = Outcome(false, "Hilt Trace: ${node.name} left unchanged: $reason")
    return try {
        val replacements = mutableMapOf<MethodNode, MethodNode>()
        for (method in node.methods.filter { it.name == INJECT_MEMBERS && it.access and METHOD_MASK == ACC_PUBLIC }) {
            val target = TARGET_DESCRIPTOR.matchEntire(method.desc)?.groupValues?.get(1)
            if (target == null || target == "java/lang/Object") continue
            if (!isStraightLine(method)) return unchanged("unrecognized shape in ${method.name}")
            val name = "${target.substringAfterLast('/').substringAfterLast('$')}.$INJECT_MEMBERS"
            val copy = copyOf(method).also { wrapWholeMethod(it, CATEGORY_INJECT, name) }
            replacements[method] = scratchFrames(node, copy, codeLimit) ?: return unchanged("${method.name} exceeds the $codeLimit byte limit")
        }
        if (replacements.isEmpty()) return unchanged("no injectMembers method wrapped")
        replacements.forEach { (old, new) -> node.methods[node.methods.indexOf(old)] = new }
        Outcome(true)
    } catch (e: Throwable) {
        unchanged("${e.javaClass.simpleName}: ${e.message}")
    }
}

/** The byte array form of [instrumentMembersInjector]: the very same array comes back unless the class was instrumented. */
internal fun transformMembersInjector(original: ByteArray, codeLimit: Int = MAX_METHOD_BYTES): TransformResult =
    transformClass(original) { instrumentMembersInjector(it, codeLimit) }

/** The visitor AGP needs: [instrumentMembersInjector] over the buffered class. */
internal fun membersInjectorVisitor(next: ClassVisitor, warn: (String) -> Unit): ClassVisitor =
    bufferedVisitor(next, warn) { instrumentMembersInjector(it) }
