package com.santimattius.android.sample.demo

import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadGraphDemoTest {

    private val observed = ArrayDeque(
        listOf(
            FrameStats(longestGapMillis = 283, jankyFrames = 1, frameIntervalMillis = 11.1),
            FrameStats(longestGapMillis = 14, jankyFrames = 0, frameIntervalMillis = 11.1),
        ),
    )
    private val sampler = FrameSampler { block ->
        block()
        observed.removeFirst()
    }

    private fun demo(time: FakeTime, main: NamedThread, default: NamedThread) = MainThreadGraphDemo(
        Provider { chain(time) },
        Provider { fanOut(time) },
        sampler,
        time,
        main.dispatcher,
        default.dispatcher,
    )

    @Test
    fun resolvesTheGraphOnTheMainDispatcherFirstAndOnTheDefaultDispatcherAfterwards() = runTest {
        val time = FakeTime()
        NamedThread("main-test").use { main ->
            NamedThread("default-test").use { default -> demo(time, main, default).run() }
        }

        assertEquals(List(8) { "main-test" } + List(8) { "default-test" }, time.threads)
    }

    @Test
    fun reportsTheMeasuredFrameGapsOfBothScenarios() = runTest {
        val result = NamedThread("main-test").use { main ->
            NamedThread("default-test").use { default -> demo(FakeTime(), main, default).run() }
        }

        assertTrue(result, result.contains("SIMULATED"))
        assertTrue(result, result.contains("measured on this device"))
        assertTrue(result, result.contains("11.1 ms per frame"))
        assertTrue(result, result.contains("On the main thread (blocking get): 270 ms, longest gap between frames 283 ms, janky frames 1"))
        assertTrue(result, result.contains("Off the main thread (Provider.suspending): 270 ms, longest gap between frames 14 ms, janky frames 0"))
    }
}
