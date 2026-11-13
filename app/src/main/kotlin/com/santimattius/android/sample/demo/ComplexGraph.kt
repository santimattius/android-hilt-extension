package com.santimattius.android.sample.demo

import com.santimattius.android.hilt.coroutines.di.DefaultDispatcher
import com.santimattius.android.hilt.coroutines.di.MainDispatcher
import com.santimattius.android.hilt.coroutines.suspend.suspending
import dagger.Lazy
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** SIMULATED work: stands in for slow constructors (I/O, parsing) so the effect is visible in a trace. */
fun interface SimulatedWork {
    fun perform(millis: Long)
}

/** Monotonic time source, injected so tests control elapsed time. */
fun interface MonotonicClock {
    fun nanoTime(): Long
}

internal object GraphCost {
    const val CHAIN_STEP_MILLIS = 30L
    const val SIBLING_MILLIS = 40L
    const val SINGLETON_MILLIS = 60L
}

// Deep chain: every level requests the next through a Provider and resolves it in its constructor.
// The Provider boundary gives each level its own SwitchingProvider case, so each has a hilt.create
// section; plain constructor injection of an unscoped binding would be inlined and leave none.
class ChainE @Inject constructor(work: SimulatedWork) {
    init { work.perform(GraphCost.CHAIN_STEP_MILLIS) }
}

class ChainD @Inject constructor(work: SimulatedWork, next: Provider<ChainE>) {
    val next: ChainE = next.get()
    init { work.perform(GraphCost.CHAIN_STEP_MILLIS) }
}

class ChainC @Inject constructor(work: SimulatedWork, next: Provider<ChainD>) {
    val next: ChainD = next.get()
    init { work.perform(GraphCost.CHAIN_STEP_MILLIS) }
}

class ChainB @Inject constructor(work: SimulatedWork, next: Provider<ChainC>) {
    val next: ChainC = next.get()
    init { work.perform(GraphCost.CHAIN_STEP_MILLIS) }
}

class ChainA @Inject constructor(work: SimulatedWork, next: Provider<ChainB>) {
    val next: ChainB = next.get()
    init { work.perform(GraphCost.CHAIN_STEP_MILLIS) }
}

// Slow siblings: independent bindings with the same simulated cost.
class SlowOne @Inject constructor(work: SimulatedWork) {
    init { work.perform(GraphCost.SIBLING_MILLIS) }
}

class SlowTwo @Inject constructor(work: SimulatedWork) {
    init { work.perform(GraphCost.SIBLING_MILLIS) }
}

class SlowThree @Inject constructor(work: SimulatedWork) {
    init { work.perform(GraphCost.SIBLING_MILLIS) }
}

/** Resolves its siblings one after another on the calling thread: the cost is their sum, not the max. */
class WideFanOut @Inject constructor(
    one: Provider<SlowOne>,
    two: Provider<SlowTwo>,
    three: Provider<SlowThree>,
) {
    val siblings: List<Any> = listOf(one.get(), two.get(), three.get())
}

/** The same dependencies behind `Lazy`: creating it is cheap, the cost moves to the first [use]. */
class DeferredGraph @Inject constructor(
    private val chain: Lazy<ChainA>,
    private val one: Lazy<SlowOne>,
    private val two: Lazy<SlowTwo>,
    private val three: Lazy<SlowThree>,
) {
    fun use() {
        chain.get()
        one.get()
        two.get()
        three.get()
    }
}

/** Slow scoped binding: built once per process, so only the first request pays. */
@Singleton
class SlowSingleton @Inject constructor(work: SimulatedWork) {
    init { work.perform(GraphCost.SINGLETON_MILLIS) }
}

/**
 * Shows how the shape of a dependency graph changes where time is spent. Every delay is simulated; the
 * point is the nesting of `hilt.create` sections in a trace, not the absolute numbers.
 */
class ComplexGraphDemo @Inject constructor(
    private val chain: Provider<ChainA>,
    private val fanOut: Provider<WideFanOut>,
    private val deferred: Provider<DeferredGraph>,
    private val singleton: Provider<SlowSingleton>,
    private val clock: MonotonicClock,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : SuspendDemo {

    // Off the main thread: the simulated work blocks the thread that resolves the graph.
    override suspend fun run(): String = withContext(dispatcher) {
        val deepChain = elapsedMillis { chain.get() }
        val wideFanOut = elapsedMillis { fanOut.get() }
        val (graph, resolve) = timed { deferred.get() }
        val firstUse = elapsedMillis { graph.use() }
        val secondUse = elapsedMillis { graph.use() }
        val firstGet = elapsedMillis { singleton.get() }
        val secondGet = elapsedMillis { singleton.get() }
        listOf(
            "SIMULATED work: constructors sleep. Read the trace shape, not the numbers.",
            "1. Deep chain (5 x ${GraphCost.CHAIN_STEP_MILLIS} ms, eager): $deepChain ms",
            "2. Wide fan-out (3 x ${GraphCost.SIBLING_MILLIS} ms, eager): $wideFanOut ms",
            "3. Same graph behind Lazy: resolve $resolve ms, first use $firstUse ms, second use $secondUse ms",
            "4. Slow @Singleton (${GraphCost.SINGLETON_MILLIS} ms): get #1 $firstGet ms, get #2 $secondGet ms " +
                "(built once per process; a later Run finds it built)",
        ).joinToString("\n")
    }

    private inline fun elapsedMillis(block: () -> Unit): Long = timed(block).second

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = clock.nanoTime()
        val value = block()
        return value to (clock.nanoTime() - start) / NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Resolves the same deep chain and wide fan-out twice and samples the frame gaps of each: once as a
 * blocking `get()` on the main thread, once through `Provider.suspending` on a worker. The work is
 * SIMULATED; the frame numbers are measured on the device that runs it.
 */
class MainThreadGraphDemo @Inject constructor(
    private val chain: Provider<ChainA>,
    private val fanOut: Provider<WideFanOut>,
    private val sampler: FrameSampler,
    private val clock: MonotonicClock,
    @MainDispatcher private val main: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : SuspendDemo {

    override suspend fun run(): String {
        // Eager and blocking on purpose: what a graph resolved in a click handler or onCreate does.
        val blocked = measure { withContext(main) { chain.get(); fanOut.get() } }
        val offMainChain = chain.suspending(default)
        val offMainFanOut = fanOut.suspending(default)
        val free = measure { offMainChain.get(); offMainFanOut.get() }
        val (blockedMillis, blockedFrames) = blocked
        val (freeMillis, freeFrames) = free
        return listOf(
            "SIMULATED work: constructors sleep. Frame gaps are measured on this device " +
                "(${"%.1f".format(Locale.ROOT, blockedFrames.frameIntervalMillis)} ms per frame; " +
                "janky = gap over 1.5 frames).",
            "On the main thread (blocking get): $blockedMillis ms, " +
                "longest gap between frames ${blockedFrames.longestGapMillis} ms, janky frames ${blockedFrames.jankyFrames}",
            "Off the main thread (Provider.suspending): $freeMillis ms, " +
                "longest gap between frames ${freeFrames.longestGapMillis} ms, janky frames ${freeFrames.jankyFrames}",
        ).joinToString("\n")
    }

    private suspend fun measure(block: suspend () -> Unit): Pair<Long, FrameStats> {
        var elapsed = 0L
        val frames = sampler.sample {
            val start = clock.nanoTime()
            block()
            elapsed = (clock.nanoTime() - start) / NANOS_PER_MILLI
        }
        return elapsed to frames
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
