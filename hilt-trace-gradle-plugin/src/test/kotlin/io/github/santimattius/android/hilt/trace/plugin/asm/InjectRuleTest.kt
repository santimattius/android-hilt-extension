package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.NOP
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V17
import org.objectweb.asm.Label
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.CheckClassAdapter

/**
 * Rule J against real Hilt 2.60.1 component implementations (application, activity, fragment, view,
 * view with fragment bindings and service) plus synthetic classes for the shapes Hilt does not emit.
 * `MembersInjector<T>` requested through DI is NOT traced (rule M is cut): the `*_MembersInjector`
 * fixtures must stay byte-identical, which is a documented limitation.
 */
class InjectRuleTest {

    private fun names(vararg types: String) = InjectionNames(types.map(::InjectionTarget))

    private fun transform(original: ByteArray, names: InjectionNames, codeLimit: Int = MAX_METHOD_BYTES) =
        transformClass(original) { instrumentAccessors(it, names::nameOf, codeLimit) }

    private fun facadeCalls(method: MethodNode) = method.instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == FACADE }.map { it.name }

    /** The method alone in a fresh class, so equal methods give equal bytes whatever surrounds them. */
    private fun isolated(method: MethodNode) = ClassWriter(0).also {
        it.visit(V17, ACC_PUBLIC, "x/X", null, "java/lang/Object", null)
        method.accept(it)
    }.toByteArray().toList()

    private fun wrappedNames(bytes: ByteArray) = readClass(bytes).methods.filter { facadeCalls(it).isNotEmpty() }.map { it.name }

    private fun assertOthersUntouched(original: ByteArray, result: ByteArray, wrapped: Set<String>) {
        val after = readClass(result).methods.associateBy { it.name + it.desc }
        val untouched = readClass(original).methods.filter { it.name !in wrapped }
        assertTrue(untouched.isNotEmpty())
        untouched.forEach { assertEquals(it.name, isolated(it), isolated(after.getValue(it.name + it.desc))) }
    }

    @Test
    fun `the public void inject method of a catalogued target is one whole method span named after the target`() {
        val original = fixtureBytes("ActivityCImpl")
        val result = transform(original, names("spike.MainActivity"))
        assertNull(result.warning)
        ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        val inject = readClass(result.bytes).methods.single { it.name == "injectMainActivity" }
        assertEquals(listOf("hilt.inject", "MainActivity.members"), inject.instructions.filterIsInstance<LdcInsnNode>().map { it.cst })
        assertEquals("hilt.inject", (inject.instructions.first { it.opcode >= 0 } as LdcInsnNode).cst)
        assertEquals(listOf("begin", "end", "end"), facadeCalls(inject))
        // The private helper injectMainActivity2 returns the instance and is left byte-identical.
        assertEquals(listOf("injectMainActivity"), wrappedNames(result.bytes))
        assertOthersUntouched(original, result.bytes, setOf("injectMainActivity"))
    }

    @Test
    fun `an empty bodied inject method is wrapped without an exception range and a return typed helper is skipped`() {
        val original = fixtureBytes("SingletonCImpl")
        // injectFieldInjected is a catalogued target's helper, but private and returning the instance.
        val result = transform(original, names("spike.SpikeApp", "spike.FieldInjected"))
        assertNull(result.warning)
        ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        val empty = readClass(result.bytes).methods.single { it.name == "injectSpikeApp" }
        assertEquals(listOf("begin", "end"), facadeCalls(empty))
        assertTrue(empty.tryCatchBlocks.isEmpty())
        assertEquals(listOf("injectSpikeApp"), wrappedNames(result.bytes))
        assertOthersUntouched(original, result.bytes, setOf("injectSpikeApp"))
    }

    @Test
    fun `fragment view and service component fixtures follow the same rule`() {
        val expected = mapOf(
            "FragmentCImpl" to ("injectSpikeFragment" to "SpikeFragment"),
            "ViewCImpl" to ("injectSpikeView" to "SpikeView"),
            "ViewWithFragmentCImpl" to ("injectSpikeFragmentView" to "SpikeFragmentView"),
            "ServiceCImpl" to ("injectSpikeService" to "SpikeService"),
        )
        for ((fixture, target) in expected) {
            val original = fixtureBytes(fixture)
            val result = transform(original, names("spike.${target.second}"))
            assertNull(fixture, result.warning)
            ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
            assertEquals(fixture, listOf(target.first), wrappedNames(result.bytes))
            val inject = readClass(result.bytes).methods.single { it.name == target.first }
            assertEquals(fixture, listOf("hilt.inject", "${target.second}.members"), inject.instructions.filterIsInstance<LdcInsnNode>().map { it.cst })
            assertOthersUntouched(original, result.bytes, setOf(target.first))
        }
    }

    @Test
    fun `an uncatalogued target or no catalogue at all returns the very same bytes`() {
        val activity = fixtureBytes("ActivityCImpl")
        for (names in listOf(names("spike.Other"), names())) {
            val result = transform(activity, names)
            assertSame(activity, result.bytes)
            assertNull(result.warning)
        }
    }

    /** A class named like a Hilt component implementation, with the methods [methods] adds. */
    private fun component(methods: ClassWriter.() -> Unit): ByteArray = framesWriter().apply {
        visit(V17, ACC_PUBLIC or ACC_SUPER, "synthetic/DaggerOuter_HiltComponents_SingletonC\$SingletonCImpl", null, "java/lang/Object", null)
        methods()
        visitEnd()
    }.toByteArray()

    private fun ClassWriter.method(access: Int, name: String, desc: String, body: MethodVisitor.() -> Unit = { visitInsn(RETURN) }) {
        visitMethod(access, name, desc, null, null).apply { visitCode(); body(); visitMaxs(0, 0); visitEnd() }
    }

    // A component with one matching method and one near miss of every other kind.
    private fun mixed(): ByteArray = component {
        val touch: MethodVisitor.() -> Unit = { visitVarInsn(ALOAD, 1); visitInsn(POP); visitInsn(RETURN) }
        method(ACC_PUBLIC, "injectA", "(Lsynthetic/A;)V", touch)
        method(ACC_PUBLIC, "injectB", "(Lsynthetic/B;)V", touch)
        method(ACC_PUBLIC, "injectAReturning", "(Lsynthetic/A;)Lsynthetic/A;") { visitVarInsn(ALOAD, 1); visitInsn(ARETURN) }
        method(ACC_PUBLIC, "injectTwo", "(Lsynthetic/A;I)V", touch)
        method(ACC_PUBLIC, "injectArray", "([Lsynthetic/A;)V", touch)
        method(ACC_PUBLIC, "register", "(Lsynthetic/A;)V", touch)
        method(ACC_PUBLIC or ACC_STATIC, "injectStatic", "(Lsynthetic/A;)V")
        method(ACC_PRIVATE, "injectPrivate", "(Lsynthetic/A;)V", touch)
    }

    @Test
    fun `only a public void instance method named inject with one catalogued parameter is wrapped`() {
        val original = mixed()
        val result = transform(original, names("synthetic.A"))
        assertNull(result.warning)
        assertEquals(listOf("injectA"), wrappedNames(result.bytes))
        assertOthersUntouched(original, result.bytes, setOf("injectA"))
    }

    @Test
    fun `member injectors are not component implementations and stay byte-identical`() {
        // MainActivity_MembersInjector.injectMembers(MainActivity) is a public void inject method of a catalogued target.
        for (fixture in listOf("MainActivity_MembersInjector", "FieldInjected_MembersInjector")) {
            val bytes = fixtureBytes(fixture)
            assertTrue(fixture, readClass(bytes).methods.any { it.name == "injectMembers" && it.desc.endsWith(";)V") })
            val result = transform(bytes, names("spike.MainActivity", "spike.FieldInjected"))
            assertSame(fixture, bytes, result.bytes)
            assertNull(result.warning)
        }
    }

    @Test
    fun `a catalogued inject method with a branch or past the size limit reverts the class with one warning`() {
        val branching = component {
            method(ACC_PUBLIC, "injectA", "(Lsynthetic/A;)V") {
                val end = Label()
                visitInsn(ICONST_0); visitJumpInsn(IFEQ, end); visitLabel(end); visitInsn(RETURN)
            }
        }
        val branch = transform(branching, names("synthetic.A"))
        assertSame(branching, branch.bytes)
        assertTrue(branch.warning, branch.warning!!.contains("unrecognized shape in injectA"))

        val big = component { method(ACC_PUBLIC, "injectA", "(Lsynthetic/A;)V") { repeat(65_520) { visitInsn(NOP) }; visitInsn(RETURN) } }
        val limited = transform(big, names("synthetic.A"))
        assertSame(big, limited.bytes)
        assertTrue(limited.warning, limited.warning!!.contains("Method too large"))
        // Fragment fixture: the same engine reverts it when the limit is tiny, so the revert is caused by the limit.
        val fragment = fixtureBytes("FragmentCImpl")
        assertSame(fragment, transform(fragment, names("spike.SpikeFragment"), codeLimit = 100).bytes)
        assertFalse(transform(fragment, names("spike.SpikeFragment")).bytes === fragment)
    }

    @Test
    fun `target names are matched on the JVM descriptor and nested classes keep their simple name`() {
        val targets = names("p.Outer.Inner", "p.Top")
        assertEquals("Inner.members", targets.nameOf("injectInner", "(Lp/Outer\$Inner;)V"))
        assertEquals("Top.members", targets.nameOf("injectTop", "(Lp/Top;)V"))
        assertNull(targets.nameOf("injectTop", "(Lp/Top;)Lp/Top;"))
        assertNull(targets.nameOf("injectTop", "(Lq/Top;)V"))
        assertNull(targets.nameOf("register", "(Lp/Top;)V"))
        assertTrue(names().isEmpty)
        assertFalse(targets.isEmpty)
    }
}
