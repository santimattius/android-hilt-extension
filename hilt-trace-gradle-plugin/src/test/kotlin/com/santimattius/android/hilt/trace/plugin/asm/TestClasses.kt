package com.santimattius.android.hilt.trace.plugin.asm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.V17
import org.objectweb.asm.tree.ClassNode

internal fun fixtureBytes(name: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name.class")) { "missing fixture $name" }.use { it.readBytes() }

internal fun readClass(bytes: ByteArray): ClassNode = ClassNode().also { ClassReader(bytes).accept(it, 0) }

internal const val SYNTHETIC = "synthetic/SwitchingProvider"

/**
 * A minimal `SwitchingProvider` shaped class whose public `get()` switches on `id` with a
 * `lookupswitch` over `0 until caseCount`. Case bodies are emitted in [layout] order, so a layout
 * different from the id order puts bodies out of id order. [body] writes the code of one case and
 * must end in `ARETURN` (or whatever the test needs). When [holes] is not empty the switch is a
 * `tableswitch` whose entries for those ids jump to the default label, as javac does for gaps.
 */
internal fun syntheticProvider(
    caseCount: Int,
    holes: Set<Int> = emptySet(),
    layout: List<Int> = (0 until caseCount).filter { it !in holes },
    body: MethodVisitor.(Int) -> Unit = { id -> visitLdcInsn("case$id"); visitInsn(ARETURN) },
): ByteArray {
    val writer = framesWriter()
    writer.visit(V17, ACC_PUBLIC or ACC_SUPER, SYNTHETIC, null, "java/lang/Object", arrayOf("dagger/internal/Provider"))
    writer.visitField(0, "id", "I", null, null).visitEnd()
    writer.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null).apply {
        visitCode()
        val default = Label()
        val labels = Array(caseCount) { if (it in holes) default else Label() }
        visitVarInsn(ALOAD, 0)
        visitFieldInsn(GETFIELD, SYNTHETIC, "id", "I")
        if (holes.isEmpty()) visitLookupSwitchInsn(default, IntArray(caseCount) { it }, labels)
        else visitTableSwitchInsn(0, caseCount - 1, default, *labels)
        layout.forEach { id ->
            visitLabel(labels[id])
            body(id)
        }
        visitLabel(default)
        visitTypeInsn(NEW, "java/lang/AssertionError")
        visitInsn(org.objectweb.asm.Opcodes.DUP)
        visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
        visitInsn(ATHROW)
        visitMaxs(0, 0)
        visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
}
