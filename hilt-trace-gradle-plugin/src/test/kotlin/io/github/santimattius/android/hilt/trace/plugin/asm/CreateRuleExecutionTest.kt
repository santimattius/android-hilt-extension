package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.HiltTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.tree.LdcInsnNode

/**
 * Runs the transformed real `$SwitchingProvider` fixtures against a recording stand-in for the
 * runtime facade. The component classes and app classes the fixtures call are synthesized by
 * [ExecutionRig]; only the `$SwitchingProvider` bytecode under test is real Hilt 2.60.1 output.
 */
class CreateRuleExecutionTest {

    private val prefix = "spike.DaggerSpikeApp_HiltComponents_SingletonC"
    private val singleton = "SingletonCImpl\$SwitchingProvider"
    private val viewModel = "ViewModelCImpl\$SwitchingProvider"

    @Before
    fun clearEvents() = HiltTrace.events.clear()

    private fun span(name: String) = listOf("begin hilt.create $name", "end $name")

    private fun ExecutionRig.singletonProvider(id: Int, outer: Any) = provider("$prefix\$SingletonCImpl\$SwitchingProvider", outer, id)

    private fun ExecutionRig.viewModelProvider(outer: Any) = provider("$prefix\$ViewModelCImpl\$SwitchingProvider", outer, 0)

    @Test
    fun `scoped binding fires its creation once however often it is resolved`() {
        val rig = rigOf(singleton)
        val scoped = rig.memoized(rig.singletonProvider(1, rig.outer()))
        repeat(3) { rig.get(scoped) }
        assertEquals(span("spike.ScopedService"), HiltTrace.events)
    }

    @Test
    fun `unscoped provider backed bindings fire on every get`() {
        val rig = rigOf(singleton)
        val outer = rig.outer()
        // id 0 is a constructor call, id 2 is a @Provides factory call: both are plain cases.
        repeat(2) { rig.get(rig.singletonProvider(0, outer)) }
        repeat(2) { rig.get(rig.singletonProvider(2, outer)) }
        assertEquals(span("spike.ViaProvider") * 2 + span("spike.SpikeProvidesModule_UnscopedProvidedFactory") * 2, HiltTrace.events)
    }

    @Test
    fun `nested creations close in LIFO order and a cached scoped dependency opens no second slice`() {
        val rig = rigOf(singleton, viewModel)
        val outer = rig.outer()
        outer.set("scopedServiceProvider", rig.memoized(rig.singletonProvider(1, outer)))
        val viewModels = rig.viewModelProvider(outer)
        rig.get(viewModels)
        assertEquals(
            listOf("begin hilt.create spike.SpikeViewModel", "begin hilt.create spike.ScopedService", "end spike.ScopedService", "end spike.SpikeViewModel"),
            HiltTrace.events,
        )
        HiltTrace.events.clear()
        rig.get(viewModels)
        assertEquals(span("spike.SpikeViewModel"), HiltTrace.events)
    }

    @Test
    fun `120 nested scoped creations run through the router and both split methods in LIFO order`() {
        val rig = rigOf(singleton)
        val outer = rig.outer()
        // Ids 4..123 are B000..B119: ids up to 99 live in get0, 100 and up in get1.
        repeat(120) { outer.set("b%03dProvider".format(it), rig.memoized(rig.singletonProvider(it + 4, outer))) }
        rig.get(rig.singletonProvider(3, outer))
        val children = (0 until 120).flatMap { span("spike.B%03d".format(it)) }
        assertEquals(listOf("begin hilt.create spike.Many") + children + "end spike.Many", HiltTrace.events)
    }

    @Test
    fun `an exception during creation still closes the slice and propagates unchanged`() {
        val rig = rigOf(singleton, viewModel)
        val boom = IllegalStateException("boom")
        val outer = rig.outer()
        outer.set("scopedServiceProvider", rig.provider { throw boom })
        try {
            rig.get(rig.viewModelProvider(outer))
            fail("the creation should have thrown")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(span("spike.SpikeViewModel"), HiltTrace.events)
        // The same for a failure deep inside the split methods: Many opens, B050 throws, Many still closes.
        HiltTrace.events.clear()
        (0 until 120).forEach { outer.set("b%03dProvider".format(it), rig.memoized(rig.singletonProvider(it + 4, outer))) }
        outer.set("b050Provider", rig.provider { throw boom })
        try {
            rig.get(rig.singletonProvider(3, outer))
            fail("the creation should have thrown")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        val children = (0 until 50).flatMap { span("spike.B%03d".format(it)) }
        assertEquals(listOf("begin hilt.create spike.Many") + children + "end spike.Many", HiltTrace.events)
    }

    @Test
    fun `the router get is not wrapped and a split provider adds one slice per resolved id`() {
        val rig = rigOf(singleton)
        val outer = rig.outer()
        // Through the router, once into get0 (id 1) and once into get1 (id 100 is B096).
        rig.get(rig.singletonProvider(1, outer))
        rig.get(rig.singletonProvider(100, outer))
        // A wrapped router would add an unnamed pair around each of the two calls.
        assertEquals(span("spike.ScopedService") + span("spike.B096"), HiltTrace.events)
    }

    @Test
    fun `lookupswitch providers for assisted factory and retained lifecycle fire their slice`() {
        val rig = rigOf("ActivityCImpl\$SwitchingProvider", "ActivityCImpl\$SwitchingProvider\$1", "ActivityRetainedCImpl\$SwitchingProvider")
        val assisted = "$prefix\$ActivityCImpl\$SwitchingProvider\$1"
        rig.get(rig.provider("$prefix\$ActivityCImpl\$SwitchingProvider", rig.outer(), 0))
        rig.get(rig.provider("$prefix\$ActivityRetainedCImpl\$SwitchingProvider", rig.outer(), 0))
        val lifecycle = "dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory"
        assertEquals(span(assisted) + span(lifecycle), HiltTrace.events)
    }

    @Test
    fun `case bodies out of id order run the slice of the case the id selects`() {
        // A lookupswitch laid out 2, 0, 1 and a tableswitch with a hole at id 1 laid out 3, 0, 2; the slice is named after the body's string.
        val nameOfString = { body: CaseBody -> generateSequence(body.label.next) { it.next }.filterIsInstance<LdcInsnNode>().first().cst as String }
        val shapes = mapOf(
            syntheticProvider(caseCount = 3, layout = listOf(2, 0, 1)) to listOf(0, 1, 2),
            syntheticProvider(caseCount = 4, holes = setOf(1), layout = listOf(3, 0, 2)) to listOf(0, 2, 3),
        )
        for ((bytes, ids) in shapes) {
            val rig = createRuleRig(listOf(bytes), nameOfString)
            for (id in ids) {
                HiltTrace.events.clear()
                assertEquals("case$id", rig.get(rig.provider(SYNTHETIC.replace('/', '.'), rig.outer(), id)))
                assertEquals(span("case$id"), HiltTrace.events)
            }
        }
    }

    @Test
    fun `bindings without a switching provider case get no span of their own`() {
        val bytes = fixtureBytes("SingletonCImpl\$SwitchingProvider")
        val transformed = readClass(transformSwitchingProvider(bytes, ::instrumentEverything).bytes)
        val names = transformed.methods.flatMap { m -> m.instructions.filterIsInstance<LdcInsnNode>().map { it.cst } }
            .filter { it != "hilt.create" }
        // Direct, the Binds target and the multibinding contribution are built inline in the component accessors.
        // Exactly the 125 jump table cases (ViaProvider, ScopedService, two @Provides, Many, B000..B119) and nothing else.
        assertEquals(125, names.size)
        assertEquals(125, names.toSet().size)
        assertTrue(names.all { it is String && it.startsWith("spike.") })
        assertTrue("spike.SpikeProvidesModule_ScopedProvidedFactory" in names)
        listOf("spike.Direct", "spike.Greeter", "spike.GreeterImpl").forEach { assertFalse(it in names) }
        // The component implementation holds the inline construction and is not a SwitchingProvider: its bytes are returned as read.
        val component = fixtureBytes("SingletonCImpl")
        assertSame(component, transformSwitchingProvider(component, ::instrumentEverything).bytes)
    }
}

private operator fun List<String>.times(n: Int) = (1..n).flatMap { this }
