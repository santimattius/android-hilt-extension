package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.HiltTrace
import io.github.santimattius.android.hilt.trace.compiler.catalogue.Binding
import io.github.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Assisted creation: Hilt builds an `@AssistedFactory` (and a `@HiltWorker` factory) as an anonymous
 * `$SwitchingProvider$N` whose `create(<assisted args>)` constructs the class. The fixtures are real
 * Hilt 2.60.1 output: `SingletonCImpl$SwitchingProvider$1` from the sample app (`AssistedWidget`) and
 * `ActivityCImpl$SwitchingProvider$1` from the spike app (`AssistedThing`, an activity scoped factory).
 */
class AssistedFactoryRuleTest {

    private val sample = "io.github.santimattius.android.sample"
    private val component = "$sample.DaggerSampleApplication_HiltComponents_SingletonC"
    private val factory = "SingletonCImpl\$SwitchingProvider\$1"
    private val widget = "$sample.demo.AssistedWidget"

    @Before
    fun clearEvents() = HiltTrace.events.clear()

    private fun ExecutionRig.sampleComponent(): Any =
        loadClass("$component\$SingletonCImpl").getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun beginNames(bytes: ByteArray): List<Any?> = readClass(bytes).methods.flatMap { m ->
        m.instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == FACADE && it.name == "begin" }.map { (it.previous as LdcInsnNode).cst }
    }

    private fun assistedWidget(kind: BindingKind = BindingKind.ASSISTED_INJECT, type: String = widget) =
        Binding(kind, type, null, null, widget, "<init>")

    private fun nameOfCreate(bindings: List<Binding>): (CaseBody) -> String? = SliceNames(bindings)::nameOf

    @Test
    fun `create of a real assisted factory is wrapped and named after the catalogued assisted class`() {
        val result = transformAssistedFactory(fixtureBytes(factory), nameOfCreate(listOf(assistedWidget(type = "catalogue.Name"))))
        assertNull(result.warning)
        assertEquals(listOf<Any?>("catalogue.Name"), beginNames(result.bytes))
    }

    @Test
    fun `without a catalogue entry the slice is named after the constructed class`() {
        val result = transformAssistedFactory(fixtureBytes(factory), nameOfCreate(emptyList()))
        assertNull(result.warning)
        assertEquals(listOf<Any?>(widget), beginNames(result.bytes))
    }

    @Test
    fun `the factory of an activity scoped component is wrapped the same way`() {
        val result = transformAssistedFactory(fixtureBytes("ActivityCImpl\$SwitchingProvider\$1"), ::fallbackName)
        assertNull(result.warning)
        assertEquals(listOf<Any?>("spike.AssistedThing"), beginNames(result.bytes))
    }

    @Test
    fun `the constructor of the factory is not wrapped and the instrumented class passes the verifier`() {
        // The rig runs the ASM data flow checker over every transformed class and the JVM verifies it when it is defined.
        assistedRuleRig(listOf(fixtureBytes(factory)))
        val node = readClass(transformAssistedFactory(fixtureBytes(factory), ::fallbackName).bytes)
        assertEquals(0, node.methods.single { it.name == "<init>" }.instructions.filterIsInstance<MethodInsnNode>().count { it.owner == FACADE })
        assertEquals(1, node.methods.single { it.name == "create" }.tryCatchBlocks.size)
    }

    @Test
    fun `creating through the factory opens one slice around the construction with the dependency inside`() {
        val rig = assistedRuleRig(listOf(fixtureBytes(factory)), nameOfCreate(emptyList()))
        val outer = rig.sampleComponent()
        outer.set("greetingRepositoryProvider", rig.provider { HiltTrace.events += "dependency created"; rig.loadClass("$sample.greeting.GreetingRepository").getDeclaredConstructor().newInstance() })
        val switching = rig.loadClass("$component\$SingletonCImpl\$SwitchingProvider").getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        switching.set("singletonCImpl", outer)
        val assisted = rig.loadClass("$component\$SingletonCImpl\$SwitchingProvider\$1").declaredConstructors.single().apply { isAccessible = true }.newInstance(switching)

        assisted.javaClass.getMethod("create", String::class.java).apply { isAccessible = true }.invoke(assisted, "label")

        assertEquals(listOf("begin hilt.create $widget", "dependency created", "end $widget"), HiltTrace.events)
    }

    @Test
    fun `a create that throws still closes its slice`() {
        val rig = assistedRuleRig(listOf(fixtureBytes(factory)), nameOfCreate(emptyList()))
        val outer = rig.sampleComponent()
        val boom = IllegalStateException("boom")
        outer.set("greetingRepositoryProvider", rig.provider { throw boom })
        val switching = rig.loadClass("$component\$SingletonCImpl\$SwitchingProvider").getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        switching.set("singletonCImpl", outer)
        val assisted = rig.loadClass("$component\$SingletonCImpl\$SwitchingProvider\$1").declaredConstructors.single().apply { isAccessible = true }.newInstance(switching)
        try {
            assisted.javaClass.getMethod("create", String::class.java).apply { isAccessible = true }.invoke(assisted, "x")
            org.junit.Assert.fail("create should have thrown")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertSame(boom, e.cause)
        }
        assertEquals(listOf("begin hilt.create $widget", "end $widget"), HiltTrace.events)
    }

    @Test
    fun `an unknown create shape returns the original bytes and one warning`() {
        val branching = syntheticAssistedFactory { visitInsn(ACONST_NULL); visitInsn(org.objectweb.asm.Opcodes.DUP); visitJumpInsn(org.objectweb.asm.Opcodes.IFNULL, it); visitLabel(it); visitInsn(ARETURN) }
        val result = transformAssistedFactory(branching, ::fallbackName)
        assertArrayEquals(branching, result.bytes)
        assertTrue(result.warning, result.warning!!.contains(ASSISTED) && result.warning.contains("unrecognized shape in create"))
    }

    @Test
    fun `a scratch class above the limit and a failing name provider both revert the class`() {
        val original = fixtureBytes(factory)
        val tiny = transformAssistedFactory(original, ::fallbackName, codeLimit = 100)
        assertArrayEquals(original, tiny.bytes)
        assertTrue(tiny.warning, tiny.warning!!.contains("limit"))
        val failing = transformAssistedFactory(original, { error("boom") })
        assertArrayEquals(original, failing.bytes)
        assertTrue(failing.warning, failing.warning!!.contains("boom"))
    }

    @Test
    fun `a name provider that declines every create leaves the class unchanged`() {
        val original = fixtureBytes(factory)
        val result = transformAssistedFactory(original, { null })
        assertArrayEquals(original, result.bytes)
        assertTrue(result.warning, result.warning!!.contains("left unchanged"))
    }

    @Test
    fun `a normal switching provider and a plain class are not touched by the assisted rule`() {
        for (name in listOf("SingletonCImpl\$SwitchingProvider", "ViewModelCImpl\$SwitchingProvider", "Hilt_MainActivity")) {
            val original = fixtureBytes(name)
            val result = transformAssistedFactory(original, ::fallbackName)
            assertSame(name, original, result.bytes)
            assertNull(name, result.warning)
        }
    }

    @Test
    fun `the create rule does not wrap the allocation of an assisted factory but keeps naming real bindings`() {
        val names = SliceNames(emptyList())
        val retained = readClass(fixtureBytes("ActivityCImpl\$SwitchingProvider"))
        val body = (locateCaseBodies(retained.methods.single { it.name == "get" }) as Dispatch.Cases).bodies.single()
        assertNull(names.nameOf(body))
        val viewModel = readClass(fixtureBytes("ViewModelCImpl\$SwitchingProvider"))
        assertEquals("spike.SpikeViewModel", names.nameOf((locateCaseBodies(viewModel.methods.single { it.name == "get" }) as Dispatch.Cases).bodies.single()))
    }

    @Test
    fun `the streaming visitor instruments a factory and replays an unknown shape unchanged with one warning`() {
        fun stream(original: ByteArray, warnings: MutableList<String>): ByteArray {
            val writer = org.objectweb.asm.ClassWriter(0)
            org.objectweb.asm.ClassReader(original).accept(assistedFactoryVisitor(writer, ::fallbackName, warnings::add), 0)
            return writer.toByteArray()
        }
        val none = mutableListOf<String>()
        assertEquals(listOf<Any?>(widget), beginNames(stream(fixtureBytes(factory), none)))
        assertEquals(emptyList<String>(), none)

        val branching = syntheticAssistedFactory { visitInsn(ACONST_NULL); visitInsn(org.objectweb.asm.Opcodes.DUP); visitJumpInsn(org.objectweb.asm.Opcodes.IFNULL, it); visitLabel(it); visitInsn(ARETURN) }
        val plain = org.objectweb.asm.ClassWriter(0).also { org.objectweb.asm.ClassReader(branching).accept(it, 0) }.toByteArray()
        val warnings = mutableListOf<String>()
        assertArrayEquals(plain, stream(branching, warnings))
        assertEquals(1, warnings.size)
    }
}
