package com.santimattius.android.sample.demo

import com.santimattius.android.hilt.coroutines.di.DefaultDispatcher
import dagger.Lazy
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
