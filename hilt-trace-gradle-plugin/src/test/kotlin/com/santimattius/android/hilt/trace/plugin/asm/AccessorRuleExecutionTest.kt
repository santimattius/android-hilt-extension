package com.santimattius.android.hilt.trace.plugin.asm

import com.santimattius.android.hilt.trace.HiltTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.InvocationTargetException

/**
 * Runs the real `SingletonCImpl` fixture, transformed by the accessor rule, against the recording
 * facade. The accessors are real Hilt 2.60.1 output; the superclass and every app class they
 * mention are synthesized by [ExecutionRig], and the providers the fields hold are test doubles.
 * The real component is PRUNED before it is loaded: only the constructor (rewritten to a plain super
 * call), `consumer`, `scoped` and one uncatalogued method stay, because the JVM verifier rejects the
 * methods whose return types need the real class hierarchy (the component builders) against the
 * synthesized classes. The construction calls inside `consumer` are not observable (synthesized
 * constructors do nothing); the provider call in the middle of the construction expression marks it.
 */
class AccessorRuleExecutionTest {

    private val transform: (ByteArray) -> TransformResult = { bytes ->
        transformClass(bytes) { instrumentAccessors(it, accessorNames()::nameOf) }
    }

    @Before
    fun clearEvents() = HiltTrace.events.clear()

    private fun component(): ExecutionRig {
        val node = readClass(fixtureBytes("SingletonCImpl"))
        val keep = setOf("<init>", "consumer", "scoped", "getDisableFragmentGetContextFix")
        node.methods.removeAll { it.name !in keep }
        // The real constructor wires every provider of the component; the tests only need an instance.
        node.methods.single { it.name == "<init>" }.apply {
            instructions = InsnList().apply {
                add(VarInsnNode(ALOAD, 0))
                add(MethodInsnNode(INVOKESPECIAL, node.superName, "<init>", "()V", false))
                add(InsnNode(RETURN))
            }
            localVariables = null
        }
        return ExecutionRig(listOf(ClassWriter(0).also { node.accept(it) }.toByteArray()), transform)
    }

    private fun Any.call(name: String): Any? {
        val method = javaClass.getDeclaredMethod(name).apply { isAccessible = true }
        return try {
            method.invoke(this)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `an accessor span stays open across the inline construction and uncatalogued methods emit nothing`() {
        val rig = component()
        val component = rig.outer()
        component.set("scopedServiceProvider", rig.provider { HiltTrace.events += "scoped provider"; null })
        component.call("getDisableFragmentGetContextFix")
        assertEquals(emptyList<String>(), HiltTrace.events)
        // consumer() builds Direct inline and asks the scoped provider for its argument: both happen inside the span.
        component.call("consumer")
        assertEquals(
            listOf("begin hilt.inject SingletonComponent.consumer", "scoped provider", "end SingletonComponent.consumer"),
            HiltTrace.events,
        )
    }

    @Test
    fun `an accessor that throws closes its span and rethrows the same exception`() {
        val rig = component()
        val component = rig.outer()
        val failure = IllegalStateException("provider failed")
        component.set("scopedServiceProvider", rig.provider { throw failure })
        try {
            component.call("scoped")
            fail("expected the provider failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("begin hilt.inject SingletonComponent.scoped", "end SingletonComponent.scoped"), HiltTrace.events)
    }
}
