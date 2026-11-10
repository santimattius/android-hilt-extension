package com.santimattius.android.sample.demo

import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SuspendProviderDemoTest {

    @Test
    fun scopedProviderReturnsTheSharedInstanceAndUnscopedCreatesNewOnes() = runTest {
        NamedThread("io-thread").use { io ->
            val shared = SingletonCounter()
            val demo = SuspendProviderDemo(
                scoped = Provider { shared },
                unscoped = Provider { FreshWidget() },
                io = io.dispatcher,
            )

            assertEquals(
                listOf(
                    "scoped: same instance on every get(): true",
                    "unscoped: new instance on every get(): true",
                    "get() ran on: io-thread",
                ),
                demo.run().lines(),
            )
        }
    }
}
