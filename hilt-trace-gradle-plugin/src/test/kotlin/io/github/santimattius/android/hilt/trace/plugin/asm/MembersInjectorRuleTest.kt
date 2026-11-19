package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.HiltTrace
import io.github.santimattius.android.hilt.trace.plugin.isMembersInjectorName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V17
import org.objectweb.asm.Label
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * The opt-in `MembersInjector.injectMembers` rule. The fixtures are real Hilt 2.60.1 output from the
 * spike app: `spike.FieldInjected_MembersInjector` (one `@Inject` field, read through a provider) and
 * `spike.MainActivity_MembersInjector` (four injected members). `injectMembers(T)` is a real instance
 * method of the generated class that the component never calls; a `MembersInjector<T>` received
 * through DI is its only caller.
 */
class MembersInjectorRuleTest {

    private val fieldInjected = "FieldInjected_MembersInjector"
    private val activity = "MainActivity_MembersInjector"

    @Before
    fun clearEvents() = HiltTrace.events.clear()

    private fun facadeCalls(bytes: ByteArray, method: String) = readClass(bytes).methods.filter { it.name == method }
        .flatMap { m -> m.instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == FACADE }.map { it.name } }

    @Test
    fun `the name filter accepts only generated members injector class names`() {
        listOf("spike.FieldInjected_MembersInjector", "MainActivity_MembersInjector", "a.b.Outer_Inner_MembersInjector")
            .forEach { assertTrue(it, isMembersInjectorName(it)) }
        listOf(
            "spike.MembersInjector", "spike.Foo_MembersInjector\$1", "spike.Foo_MembersInjectorX", "spike.Foo_Factory",
            "spike.Hilt_Foo", "dagger.MembersInjector",
        ).forEach { assertFalse(it, isMembersInjectorName(it)) }
    }

    @Test
    fun `injectMembers of a real members injector is one whole method span named after the target`() {
        val result = transformMembersInjector(fixtureBytes(fieldInjected))
        assertNull(result.warning)
        val inject = readClass(result.bytes).methods.single { it.name == "injectMembers" && it.desc == "(Lspike/FieldInjected;)V" }
        assertEquals(listOf("hilt.inject", "FieldInjected.injectMembers"), inject.instructions.filterIsInstance<LdcInsnNode>().map { it.cst })
        assertEquals(listOf("begin", "end", "end"), facadeCalls(result.bytes, "injectMembers"))
    }

    @Test
    fun `the bridge, the static setters, create and the constructor are left alone`() {
        val original = fixtureBytes(activity)
        val result = transformMembersInjector(original)
        assertNull(result.warning)
        val before = readClass(original).methods.associateBy { it.name + it.desc }
        val after = readClass(result.bytes).methods.associateBy { it.name + it.desc }
        val wrapped = "injectMembers(Lspike/MainActivity;)V"
        assertEquals(before.keys, after.keys)
        for (key in before.keys.filter { it != wrapped }) {
            assertEquals(key, before.getValue(key).instructions.size(), after.getValue(key).instructions.size())
        }
        assertEquals(listOf("begin", "end", "end"), facadeCalls(result.bytes, "injectMembers"))
        assertEquals("MainActivity.injectMembers", readClass(result.bytes).methods
            .single { it.name == "injectMembers" && it.desc == "(Lspike/MainActivity;)V" }.instructions.filterIsInstance<LdcInsnNode>().last().cst)
    }

    @Test
    fun `calling injectMembers opens one slice with the dependency created inside it and closes it when it throws`() {
        val rig = ExecutionRig(listOf(fixtureBytes(fieldInjected)), ::transformMembersInjector)
        val injector = rig.membersInjector { HiltTrace.events += "dependency created"; rig.loadClass("spike.ScopedService").getDeclaredConstructor().newInstance() }
        val target = rig.loadClass("spike.FieldInjected").getDeclaredConstructor().newInstance()

        injector.javaClass.getMethod("injectMembers", target.javaClass).invoke(injector, target)
        assertEquals(listOf("begin hilt.inject FieldInjected.injectMembers", "dependency created", "end FieldInjected.injectMembers"), HiltTrace.events)

        HiltTrace.events.clear()
        val boom = IllegalStateException("boom")
        val failing = rig.membersInjector { throw boom }
        try {
            failing.javaClass.getMethod("injectMembers", target.javaClass).invoke(failing, target)
            org.junit.Assert.fail("injectMembers should have thrown")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertSame(boom, e.cause)
        }
        assertEquals(listOf("begin hilt.inject FieldInjected.injectMembers", "end FieldInjected.injectMembers"), HiltTrace.events)
    }

    @Test
    fun `the erased bridge reaches the wrapped method so a call through MembersInjector is traced once`() {
        val rig = ExecutionRig(listOf(fixtureBytes(fieldInjected)), ::transformMembersInjector)
        val injector = rig.membersInjector { rig.loadClass("spike.ScopedService").getDeclaredConstructor().newInstance() }
        val target = rig.loadClass("spike.FieldInjected").getDeclaredConstructor().newInstance()

        injector.javaClass.getMethod("injectMembers", Any::class.java).invoke(injector, target)

        assertEquals(listOf("begin hilt.inject FieldInjected.injectMembers", "end FieldInjected.injectMembers"), HiltTrace.events)
    }

    @Test
    fun `an unknown shape returns the original bytes and one warning`() {
        val branching = syntheticMembersInjector { visitVarInsn(ILOAD, 1); visitJumpInsn(IFEQ, it); visitLabel(it); visitInsn(RETURN) }
        val result = transformMembersInjector(branching)
        assertArrayEquals(branching, result.bytes)
        assertTrue(result.warning, result.warning!!.contains(SYNTHETIC_INJECTOR) && result.warning.contains("unrecognized shape in injectMembers"))
    }

    @Test
    fun `a method over the limit and a failure both revert the class with one warning`() {
        val original = fixtureBytes(fieldInjected)
        val tiny = transformMembersInjector(original, codeLimit = 10)
        assertArrayEquals(original, tiny.bytes)
        assertTrue(tiny.warning, tiny.warning!!.contains("limit"))
        val garbage = transformMembersInjector(byteArrayOf(1, 2, 3))
        assertTrue(garbage.warning, garbage.warning!!.contains("left unchanged"))
    }

    @Test
    fun `a members injector class without a recognizable injectMembers warns and a plain class passes silently`() {
        val none = syntheticMembersInjector(desc = "(Ljava/lang/Object;)V") { visitInsn(RETURN) }
        val result = transformMembersInjector(none)
        assertArrayEquals(none, result.bytes)
        assertTrue(result.warning, result.warning!!.contains("no injectMembers method wrapped"))
        for (name in listOf("ActivityCImpl", "SingletonCImpl\$SwitchingProvider", "Hilt_MainActivity", "FieldInjected_Factory")) {
            val bytes = fixtureBytes(name)
            val passed = transformMembersInjector(bytes)
            assertSame(name, bytes, passed.bytes)
            assertNull(name, passed.warning)
        }
    }

    /** A class named like a generated members injector whose `injectMembers(synthetic.Target)` runs [body]. */
    private fun syntheticMembersInjector(desc: String = "(Lsynthetic/Target;)V", body: org.objectweb.asm.MethodVisitor.(Label) -> Unit): ByteArray {
        val writer = framesWriter()
        writer.visit(V17, ACC_PUBLIC or ACC_SUPER, SYNTHETIC_INJECTOR, null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC, "injectMembers", desc, null, null).apply {
            visitCode()
            body(Label())
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        const val SYNTHETIC_INJECTOR = "synthetic/Target_MembersInjector"
    }
}
