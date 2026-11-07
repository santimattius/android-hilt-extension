package com.santimattius.android.hilt.trace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClassFilterTest {

    private val component = "DaggerSpikeApp_HiltComponents_SingletonC"

    @Test
    fun `accepts SwitchingProvider component implementation and Hilt base classes`() {
        listOf(
            "spike.app.$component\$SingletonCImpl\$SwitchingProvider",
            "spike.app.$component\$ActivityRetainedCImpl\$SwitchingProvider",
            "spike.app.$component\$ActivityCImpl",
            "spike.app.Hilt_MainActivity",
            "Hilt_Application",
        ).forEach { assertTrue(it, isInstrumentableClassName(it)) }
    }

    @Test
    fun `rejects MembersInjector and other classes`() {
        listOf(
            "spike.app.MainActivity_MembersInjector",
            "spike.app.MainActivity",
            "spike.app.$component\$ActivityCImpl\$SwitchingProvider\$1",
            "spike.app.$component",
            "spike.hilt_x.Plain",
            "spike.Dagger.Foo_HiltComponents_SingletonC\$ActivityCImpl",
            "spike.app.NotHilt_Base",
        ).forEach { assertFalse(it, isInstrumentableClassName(it)) }
    }

    @Test
    fun `selects exactly the matching real fixtures`() {
        val fixtures = File(javaClass.getResource("/fixtures")!!.toURI())
        val accepted = fixtures.listFiles { f -> f.extension == "class" }!!
            .map { it.name.removeSuffix(".class") }
            .filter { isInstrumentableClassName("spike.app.$component\$$it") || isInstrumentableClassName("spike.app.$it") }
            .sorted()
        assertEquals(
            listOf(
                "ActivityCImpl", "ActivityCImpl\$SwitchingProvider", "ActivityRetainedCImpl\$SwitchingProvider",
                "Hilt_MainActivity", "SingletonCImpl", "SingletonCImpl\$SwitchingProvider",
                "ViewModelCImpl\$SwitchingProvider",
            ),
            accepted,
        )
    }

    @Test
    fun `isInstrumentable path does not allocate`() {
        val names = arrayOf("spike.app.$component\$ActivityCImpl\$SwitchingProvider", "spike.app.MainActivity")
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        // Index loops over an array: a lambda, function reference or list iterator would allocate in the test itself.
        for (i in 0 until 20_000) for (name in names) isInstrumentableClassName(name)
        val before = bean.currentThreadAllocatedBytes
        for (i in 0 until 10_000) for (name in names) isInstrumentableClassName(name)
        val allocated = bean.currentThreadAllocatedBytes - before
        // One object per call would add at least 20,000 * 16 bytes. The bound only absorbs one-off noise of
        // the measurement itself, which differs between JDKs and machines (an exact zero failed on CI runners).
        assertTrue("allocated $allocated bytes over 20,000 calls", allocated < 4_096L)
    }
}
