package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.HiltTrace
import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import io.github.santimattius.android.hilt.trace.plugin.isComponentImplementationName
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
 * Runs the real `ActivityCImpl` fixture, transformed by rule J, together with the real
 * `$SwitchingProvider` fixtures, transformed by rule C, against the recording facade. The component
 * is PRUNED to `injectMainActivity` and its private helper (the verifier rejects the other methods
 * against synthesized neighbours) and its constructor becomes a plain super call; the superclass and
 * the app classes are synthesized by [ExecutionRig].
 */
class InjectRuleExecutionTest {

    private val prefix = "spike.DaggerSpikeApp_HiltComponents_SingletonC"
    private val assistedFactory = "$prefix\$ActivityCImpl\$SwitchingProvider\$1"

    @Before
    fun clearEvents() = HiltTrace.events.clear()

    private fun span(name: String) = listOf("begin hilt.create $name", "end $name")

    private fun rig(target: String): ExecutionRig {
        val node = readClass(fixtureBytes("ActivityCImpl"))
        node.methods.removeAll { it.name !in setOf("<init>", "injectMainActivity", "injectMainActivity2") }
        node.methods.single { it.name == "<init>" }.apply {
            instructions = InsnList().apply {
                add(VarInsnNode(ALOAD, 0))
                add(MethodInsnNode(INVOKESPECIAL, node.superName, "<init>", "()V", false))
                add(InsnNode(RETURN))
            }
            localVariables = null
        }
        val names = InjectionNames(listOf(InjectionTarget(target)))
        val providers = listOf("ActivityCImpl\$SwitchingProvider", "ActivityCImpl\$SwitchingProvider\$1", "SingletonCImpl\$SwitchingProvider").map(::fixtureBytes)
        return ExecutionRig(listOf(ClassWriter(0).also { node.accept(it) }.toByteArray()) + providers) { bytes ->
            if (isComponentImplementationName(readClass(bytes).name.replace('/', '.'))) transformClass(bytes) { instrumentAccessors(it, names::nameOf) }
            else transformSwitchingProvider(bytes, ::instrumentEverything)
        }
    }

    /** The activity component whose factory provider is the real, rule C transformed, `ActivityCImpl$SwitchingProvider` unless [factory] is given. */
    private fun ExecutionRig.activityComponent(factory: Any = provider("$prefix\$ActivityCImpl\$SwitchingProvider", outer(), 0)): Any {
        val component = loadClass("$prefix\$ActivityCImpl").declaredConstructors.single().apply { isAccessible = true }.newInstance(null, null, null)
        component.set("singletonCImpl", outer())
        component.set("factoryProvider", factory)
        return component
    }

    private fun Any.call(name: String) {
        val method = javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }
        val target = javaClass.classLoader.loadClass("spike.MainActivity").getDeclaredConstructor().newInstance()
        try {
            method.invoke(this, target)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `a creation inside the inject method nests in the inject span and the private helper alone opens none`() {
        val rig = rig("spike.MainActivity")
        rig.activityComponent().call("injectMainActivity")
        assertEquals(
            listOf("begin hilt.inject MainActivity.members") + span(assistedFactory) + "end MainActivity.members",
            HiltTrace.events,
        )
        HiltTrace.events.clear()
        rig.activityComponent().call("injectMainActivity2")
        assertEquals(span(assistedFactory), HiltTrace.events)
    }

    @Test
    fun `an exception inside the inject method closes its span and propagates unchanged`() {
        val rig = rig("spike.MainActivity")
        val failure = IllegalStateException("factory failed")
        try {
            rig.activityComponent(rig.provider { throw failure }).call("injectMainActivity")
            fail("expected the factory failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("begin hilt.inject MainActivity.members", "end MainActivity.members"), HiltTrace.events)
    }

    @Test
    fun `an uncatalogued target emits no inject slice and a repeated provider get emits one creation each`() {
        val uncatalogued = rig("spike.Other")
        uncatalogued.activityComponent().call("injectMainActivity")
        assertEquals(span(assistedFactory), HiltTrace.events)
        HiltTrace.events.clear()
        // An unscoped Provider.get called twice: two creations and, with the target catalogued, still no inject slice.
        val rig = rig("spike.MainActivity")
        val outer = rig.outer()
        repeat(2) { rig.get(rig.provider("$prefix\$SingletonCImpl\$SwitchingProvider", outer, 0)) }
        assertEquals(span("spike.ViaProvider") + span("spike.ViaProvider"), HiltTrace.events)
    }
}
