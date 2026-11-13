package com.santimattius.android.sample.demo

import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulated work advances a fake clock instead of sleeping, so elapsed times are exact and instant. */
internal class FakeTime : SimulatedWork, MonotonicClock {
    var nanos = 0L
    val performed = mutableListOf<Long>()
    val threads = mutableListOf<String>()

    override fun perform(millis: Long) {
        performed += millis
        threads += Thread.currentThread().name
        nanos += millis * NANOS_PER_MILLI
    }

    override fun nanoTime(): Long = nanos
}

internal const val NANOS_PER_MILLI = 1_000_000L

internal fun chain(time: FakeTime) = ChainA(
    time,
    Provider {
        ChainB(
            time,
            Provider { ChainC(time, Provider { ChainD(time, Provider { ChainE(time) }) }) },
        )
    },
)

internal fun fanOut(time: FakeTime) = WideFanOut(
    Provider { SlowOne(time) },
    Provider { SlowTwo(time) },
    Provider { SlowThree(time) },
)

/** A `dagger.Lazy` that, like Dagger's, computes once and then returns the same instance. */
private fun <T> cached(create: () -> T): dagger.Lazy<T> {
    val value = lazy(create)
    return dagger.Lazy { value.value }
}

private fun deferred(time: FakeTime) = DeferredGraph(
    cached { chain(time) },
    cached { SlowOne(time) },
    cached { SlowTwo(time) },
    cached { SlowThree(time) },
)

class ComplexGraphDemoTest {

    /** The singleton provider caches its instance, as a scoped Dagger binding does. */
    private fun demo(time: FakeTime): ComplexGraphDemo {
        val singleton = lazy { SlowSingleton(time) }
        return ComplexGraphDemo(
            Provider { chain(time) },
            Provider { fanOut(time) },
            Provider { deferred(time) },
            Provider { singleton.value },
            time,
            Dispatchers.Unconfined,
        )
    }

    @Test
    fun theChainSumsEveryLevelWhenResolvedEagerly() {
        val time = FakeTime()

        chain(time)

        assertEquals(List(5) { GraphCost.CHAIN_STEP_MILLIS }, time.performed)
    }

    @Test
    fun theFanOutSumsEverySiblingWhenResolvedEagerly() {
        val time = FakeTime()

        fanOut(time)

        assertEquals(List(3) { GraphCost.SIBLING_MILLIS }, time.performed)
    }

    @Test
    fun deferredGraphDoesNoWorkUntilItIsUsed() {
        val time = FakeTime()

        val graph = deferred(time)
        assertTrue(time.performed.isEmpty())

        graph.use()
        assertEquals(5 * GraphCost.CHAIN_STEP_MILLIS + 3 * GraphCost.SIBLING_MILLIS, time.performed.sum())
        graph.use()
        assertEquals(8, time.performed.size) // the second use created nothing
    }

    @Test
    fun reportsMeasuredTimePerScenario() = runTest {
        val result = demo(FakeTime()).run()

        assertTrue(result, result.contains("SIMULATED"))
        assertTrue(result, result.contains("Deep chain (5 x 30 ms, eager): 150 ms"))
        assertTrue(result, result.contains("Wide fan-out (3 x 40 ms, eager): 120 ms"))
        assertTrue(result, result.contains("resolve 0 ms, first use 270 ms, second use 0 ms"))
    }

    @Test
    fun reportsTheSingletonCostOnlyOnTheFirstGet() = runTest {
        val result = demo(FakeTime()).run()

        assertTrue(result, result.contains("get #1 60 ms, get #2 0 ms"))
    }
}
