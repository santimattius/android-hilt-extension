package io.github.santimattius.android.hilt.trace.plugin.asm

import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.CheckClassAdapter

class CreateRuleTest {

    private val singleton = "SingletonCImpl\$SwitchingProvider"

    /** Wraps every located case body, writes the class with computed frames and runs the ASM data flow checker over it. */
    private fun wrapped(fixture: String): ClassNode {
        val node = readClass(fixtureBytes(fixture))
        node.methods.forEach { m -> (locateCaseBodies(m) as? Dispatch.Cases)?.let { wrapCaseBodies(m, it.bodies, ::fallbackName) } }
        val writer = framesWriter().also { node.accept(it) }
        ClassReader(writer.toByteArray()).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        return readClass(writer.toByteArray())
    }

    private fun ClassNode.method(name: String): MethodNode = methods.single { it.name == name }

    private fun MethodNode.calls(name: String) =
        instructions.filterIsInstance<MethodInsnNode>().count { it.owner == FACADE && it.name == name }

    private fun MethodNode.beginNames() = instructions.filterIsInstance<MethodInsnNode>()
        .filter { it.owner == FACADE && it.name == "begin" }.map { (it.previous as LdcInsnNode).cst }

    @Test
    fun `split methods wrap every case body and leave the router untouched`() {
        val node = wrapped(singleton)
        assertEquals(100, node.method("get0").calls("begin"))
        assertEquals(25, node.method("get1").calls("begin"))
        // Each body closes on the normal path and in the catch-all handler.
        assertEquals(200, node.method("get0").calls("end"))
        assertEquals(50, node.method("get1").calls("end"))
        assertEquals(0, node.method("get").calls("begin") + node.method("get").calls("end"))
        assertEquals(125, node.method("get0").tryCatchBlocks.size + node.method("get1").tryCatchBlocks.size)
    }

    @Test
    fun `end runs before the return and again in the catch-all handler`() {
        val get = wrapped("ActivityRetainedCImpl\$SwitchingProvider").method("get")
        val body = (locateCaseBodies(get) as Dispatch.Cases).bodies.single()
        val events = generateSequence(body.label.next) { it.next }.mapNotNull {
            when {
                it is MethodInsnNode -> it.name
                it.opcode == ARETURN -> "ARETURN"
                it.opcode == ATHROW -> "ATHROW"
                else -> null
            }
        }.toList()
        // The handler ends at the first ATHROW; the default branch (AssertionError) comes after it.
        assertEquals(listOf("begin", "provideActivityRetainedLifecycle", "end", "ARETURN", "end", "ATHROW"), events.take(events.indexOf("ATHROW") + 1))
        assertEquals(null, get.tryCatchBlocks.single().type)
    }

    @Test
    fun `slices are named after the first constructed type or called factory`() {
        val names = wrapped(singleton).method("get0").beginNames()
        assertEquals(
            listOf("spike.ViaProvider", "spike.ScopedService", "spike.SpikeProvidesModule_UnscopedProvidedFactory", "spike.Many"),
            names.take(4),
        )
        val prefix = "spike.DaggerSpikeApp_HiltComponents_SingletonC"
        assertEquals(listOf("$prefix\$ActivityCImpl\$SwitchingProvider\$1"), wrapped("ActivityCImpl\$SwitchingProvider").method("get").beginNames())
        assertEquals(listOf("spike.SpikeViewModel"), wrapped("ViewModelCImpl\$SwitchingProvider").method("get").beginNames())
    }
}
