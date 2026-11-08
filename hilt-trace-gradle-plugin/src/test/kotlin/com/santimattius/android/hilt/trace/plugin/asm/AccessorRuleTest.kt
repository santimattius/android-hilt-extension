package com.santimattius.android.hilt.trace.plugin.asm

import com.santimattius.android.hilt.trace.compiler.catalogue.EntryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.NOP
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V17
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.util.CheckClassAdapter
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter

internal const val SINGLETON_COMPONENT = "dagger.hilt.components.SingletonComponent"

/** The `@EntryPoint` methods of the spike (`SpikeEntryPoint`) with the JVM descriptors Hilt 2.60.1 generated for them. */
internal val SPIKE_ENTRY_POINTS = listOf(
    "consumer" to "()Lspike/Consumer;",
    "scoped" to "()Lspike/ScopedService;",
    "direct" to "()Lspike/Direct;",
    "greeter" to "()Lspike/Greeter;",
    "greeters" to "()Ljava/util/Set;",
    "viaProvider" to "()Ljavax/inject/Provider;",
    "unscopedProvided" to "()Lspike/Provided;",
    "many" to "()Lspike/Many;",
    "fieldInjected" to "()Lspike/FieldInjected;",
    "provided" to "()Lspike/Provided;",
    "unscopedProvidedProvider" to "()Ljavax/inject/Provider;",
)

internal fun spikeEntryPoint(name: String, descriptor: String) = EntryPoint("spike.SpikeEntryPoint", name, descriptor, SINGLETON_COMPONENT)

internal fun accessorNames(pairs: List<Pair<String, String>> = SPIKE_ENTRY_POINTS) =
    AccessorNames(pairs.map { (name, descriptor) -> spikeEntryPoint(name, descriptor) })

class AccessorRuleTest {

    private val singleton = fixtureBytes("SingletonCImpl")

    private fun transform(original: ByteArray, names: AccessorNames, codeLimit: Int = MAX_METHOD_BYTES) =
        transformClass(original) { instrumentAccessors(it, names::nameOf, codeLimit) }

    private fun facadeCalls(method: MethodNode) = method.instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == FACADE }

    private fun text(method: MethodNode): String {
        val writer = StringWriter()
        Textifier().also { method.accept(TraceMethodVisitor(it)) }.print(PrintWriter(writer))
        return writer.toString()
    }

    @Test
    fun `catalogued accessors are wrapped as one whole method hilt inject span named after the component`() {
        val result = transform(singleton, accessorNames())
        assertNull(result.warning)
        ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        val wrapped = readClass(result.bytes).methods.filter { facadeCalls(it).isNotEmpty() }
        assertEquals(SPIKE_ENTRY_POINTS.map { it.first }.sorted(), wrapped.map { it.name }.sorted())
        wrapped.forEach { m ->
            assertEquals(m.name, listOf("hilt.inject", "SingletonComponent.${m.name}"), m.instructions.filterIsInstance<LdcInsnNode>().map { it.cst })
            assertEquals(m.name, 1, facadeCalls(m).count { it.name == "begin" })
            // The span opens before the first original instruction and closes before every return, plus the handler.
            assertEquals(m.name, "hilt.inject", (m.instructions.first { it.opcode >= 0 } as LdcInsnNode).cst)
            assertEquals(m.name, m.instructions.count { it.opcode == ARETURN } + 1, facadeCalls(m).count { it.name == "end" })
        }
    }

    @Test
    fun `uncatalogued methods and private helpers keep their exact bytecode`() {
        // Private helpers listed by name and descriptor are still not component accessors.
        val helpers = listOf("injectFieldInjected" to "(Lspike/FieldInjected;)Lspike/FieldInjected;", "initialize" to "()V")
        val before = readClass(singleton)
        val after = readClass(transform(singleton, accessorNames(SPIKE_ENTRY_POINTS + helpers)).bytes)
        val untouched = before.methods.filter { m -> SPIKE_ENTRY_POINTS.none { it.first == m.name } }
        assertTrue(untouched.map { it.name }.containsAll(listOf("injectFieldInjected", "initialize", "injectSpikeApp", "retainedComponentBuilder")))
        untouched.forEach { m -> assertEquals(m.name, text(m), text(after.methods.single { it.name == m.name && it.desc == m.desc })) }
    }

    @Test
    fun `a name match with another descriptor is not an accessor and no catalogue changes nothing`() {
        val mismatched = accessorNames(listOf("consumer" to "()Lspike/Other;", "scoped" to "(I)Lspike/ScopedService;"))
        for (names in listOf(mismatched, accessorNames(emptyList()))) {
            val result = transform(singleton, names)
            assertSame(singleton, result.bytes)
            assertNull(result.warning)
        }
    }

    @Test
    fun `the router get of a switching provider gets no inject span`() {
        // `get()` has the descriptor of a catalogued provision method here; only the component implementation class qualifies.
        val provider = fixtureBytes("SingletonCImpl\$SwitchingProvider")
        val result = transform(provider, accessorNames(listOf("get" to "()Ljava/lang/Object;")))
        assertSame(provider, result.bytes)
        assertNull(result.warning)
    }

    @Test
    fun `an inline construction gets no slice of its own and sits inside the accessor span`() {
        val ops = readClass(transform(singleton, accessorNames()).bytes).methods.single { it.name == "direct" }.instructions.filter { it.opcode >= 0 }
        val begin = ops.indexOfFirst { it is MethodInsnNode && it.name == "begin" }
        val construction = ops.indexOfFirst { it is TypeInsnNode && it.desc == "spike/Direct" }
        val end = ops.indexOfFirst { it is MethodInsnNode && it.name == "end" }
        assertTrue("$begin < $construction < $end", begin in 0 until construction && construction < end)
        assertFalse(ops.any { it is LdcInsnNode && it.cst == "hilt.create" })
    }

    private fun component(name: String = "foo", descriptor: String = "()Ljava/lang/String;", body: MethodVisitor.() -> Unit): ByteArray {
        val writer = framesWriter()
        writer.visit(V17, ACC_PUBLIC or ACC_SUPER, "synthetic/DaggerOuter_HiltComponents_SingletonC\$SingletonCImpl", null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC, name, descriptor, null, null).apply { visitCode(); body(); visitMaxs(0, 0); visitEnd() }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private val foo = accessorNames(listOf("foo" to "()Ljava/lang/String;"))

    @Test
    fun `an accessor with branching returns the original bytes and one warning`() {
        val branching = component {
            val other = Label()
            visitInsn(ICONST_0)
            visitJumpInsn(IFEQ, other)
            visitLdcInsn("a")
            visitInsn(ARETURN)
            visitLabel(other)
            visitLdcInsn("b")
            visitInsn(ARETURN)
        }
        val result = transform(branching, foo)
        assertSame(branching, result.bytes)
        assertTrue(result.warning, result.warning!!.contains("unrecognized shape in foo"))
        // The same accessor without a branch is wrapped, so the revert above is caused by the branch.
        assertTrue(transform(component { visitLdcInsn("a"); visitInsn(ARETURN) }, foo).warning == null)
    }

    @Test
    fun `an empty bodied entry point method is wrapped without an empty exception range`() {
        val empty = component("inject", "(Lspike/MainActivity;)V") { visitInsn(RETURN) }
        val result = transform(empty, accessorNames(listOf("inject" to "(Lspike/MainActivity;)V")))
        assertNull(result.warning)
        // CheckClassAdapter rejects a try block whose start and end labels are the same offset.
        ClassReader(result.bytes).accept(CheckClassAdapter(ClassWriter(0), true), 0)
        val inject = readClass(result.bytes).methods.single { it.name == "inject" }
        assertEquals(listOf("begin", "end"), facadeCalls(inject).map { it.name })
        assertTrue(inject.tryCatchBlocks.isEmpty())
    }

    @Test
    fun `an accessor that would pass the method size limit reverts the class`() {
        val big = component { repeat(65_520) { visitInsn(NOP) }; visitInsn(ACONST_NULL); visitInsn(ARETURN) }
        val result = transform(big, foo)
        assertSame(big, result.bytes)
        assertTrue(result.warning, result.warning!!.contains("Method too large"))
        val limited = transform(singleton, accessorNames(), codeLimit = 100)
        assertSame(singleton, limited.bytes)
        assertTrue(limited.warning, limited.warning!!.contains("limit"))
    }

    @Test
    fun `any throwable returns the original bytes with its message`() {
        val failing = transformClass(singleton) { instrumentAccessors(it, { _, _ -> error("boom") }) }
        assertSame(singleton, failing.bytes)
        assertTrue(failing.warning, failing.warning!!.contains("boom"))
    }
}
