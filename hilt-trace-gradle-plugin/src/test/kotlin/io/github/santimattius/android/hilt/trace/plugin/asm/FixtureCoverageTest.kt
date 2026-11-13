package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.plugin.isComponentImplementationName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC

/**
 * The real Hilt 2.60.1 component implementations that rule J is tested against. Every kind of
 * component that injects an Android class has to be represented: the checked-in classes are compiler
 * output, never hand-written, so a shape that differs between kinds shows up here and in the rule tests.
 */
class FixtureCoverageTest {

    /** Fixture file to the public `inject*` method (name and descriptor) that field-injects a catalogued target. */
    private val injectMethods = mapOf(
        "SingletonCImpl" to ("injectSpikeApp" to "(Lspike/SpikeApp;)V"),
        "ActivityCImpl" to ("injectMainActivity" to "(Lspike/MainActivity;)V"),
        "FragmentCImpl" to ("injectSpikeFragment" to "(Lspike/SpikeFragment;)V"),
        "ViewCImpl" to ("injectSpikeView" to "(Lspike/SpikeView;)V"),
        "ViewWithFragmentCImpl" to ("injectSpikeFragmentView" to "(Lspike/SpikeFragmentView;)V"),
        "ServiceCImpl" to ("injectSpikeService" to "(Lspike/SpikeService;)V"),
    )

    @Test
    fun `component fixtures exist for the application activity fragment view and service kinds`() {
        injectMethods.forEach { (fixture, expected) ->
            val node = readClass(fixtureBytes(fixture))
            assertTrue(node.name, isComponentImplementationName(node.name.replace('/', '.')))
            val inject = node.methods.singleOrNull { it.name == expected.first && it.desc == expected.second }
            assertNotNull("$fixture has no ${expected.first}${expected.second}", inject)
            assertEquals(fixture, ACC_PUBLIC, inject!!.access and (ACC_PUBLIC or ACC_STATIC or ACC_SYNTHETIC))
        }
    }
}
