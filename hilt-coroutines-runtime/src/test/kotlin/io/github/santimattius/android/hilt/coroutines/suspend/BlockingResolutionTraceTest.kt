@file:OptIn(DelicateTracingApi::class, ExperimentalCoroutinesApi::class)

package io.github.santimattius.android.hilt.coroutines.suspend

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `Provider.suspending` and `Lazy.await` open a section around the blocking resolution, so a
 * creation slice recorded inside `get()` nests under it. The fixture is the same recording
 * [PerfettoTracer] that `TraceCoroutineTest` uses.
 */
class BlockingResolutionTraceTest {

    private class Event(val type: Int, val category: String?, val name: String?)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Event>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach { recorded += Event(it.type, it.primaryCategory, it.name) }
            }
            pooledPacketArray.recycle()
        }

        override fun onDroppedTraceEvent() = Unit
        override fun flush() = Unit
        override fun close() = Unit

        fun slices(): List<Event> =
            synchronized(recorded) { recorded.filter { it.type == BEGIN || it.type == END } }
    }

    private val sink = RecordingSink()
    private var context: TraceContext? = null

    @Before
    fun resetGlobalTracer() {
        Tracer.resetGlobalTracer()
    }

    @After
    fun tearDown() {
        Tracer.resetGlobalTracer()
        context?.close()
    }

    private fun install(enabled: (String) -> Boolean) {
        val traceContext = TraceContext(sink, true, enabled)
        traceContext.createProcessTrack(id = 1, name = "blocking-resolution-trace-test")
        context = traceContext
        Tracer.setGlobalTracer(PerfettoTracer(traceContext, enabled))
    }

    private fun recorded(): List<Event> {
        context?.flush()
        return sink.slices()
    }

    /** What the sink holds while `get()` runs, so the test can tell whether the section is open. */
    private fun openNamesNow(): List<String?> {
        val slices = recorded()
        return slices.filter { it.type == BEGIN }.map { it.name }
            .also { assertEquals("no section may be closed while get() runs", 0, slices.count { it.type == END }) }
    }

    @Test
    fun providerSuspendingOpensItsSectionAroundGetAndReturnsTheValue() = runTest {
        install { it == CATEGORY }
        var openDuringGet: List<String?> = emptyList()
        val provider = Provider { openNamesNow().also { openDuringGet = it }; "value" }

        val result = provider.suspending(UnconfinedTestDispatcher(testScheduler)).get()

        assertEquals("value", result)
        assertEquals(listOf("Provider.suspending"), openDuringGet)
        val slices = recorded()
        assertEquals(listOf(BEGIN, END), slices.map { it.type })
        assertEquals(CATEGORY, slices.first().category)
    }

    @Test
    fun lazyAwaitOpensItsSectionAroundGetAndReturnsTheValue() = runTest {
        install { it == CATEGORY }
        var openDuringGet: List<String?> = emptyList()
        val lazy = Lazy { openNamesNow().also { openDuringGet = it }; "value" }

        val result = lazy.await(UnconfinedTestDispatcher(testScheduler))

        assertEquals("value", result)
        assertEquals(listOf("Lazy.await"), openDuringGet)
        assertEquals(listOf(BEGIN, END), recorded().map { it.type })
    }

    @Test
    fun aFailingGetStillReachesTheCallerAndTheSectionCloses() = runTest {
        install { it == CATEGORY }
        val provider = Provider<String> { throw IllegalStateException("boom") }

        try {
            provider.suspending(UnconfinedTestDispatcher(testScheduler)).get()
            fail("expected the exception to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }

        assertEquals(listOf(BEGIN, END), recorded().map { it.type })
    }

    @Test
    fun withTheCategoryDisabledTheValueIsStillReturnedAndNothingIsRecorded() = runTest {
        install { false }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        assertEquals(1, Provider { 1 }.suspending(dispatcher).get())
        assertEquals(2, Lazy { 2 }.await(dispatcher))

        assertTrue(recorded().isEmpty())
    }

    private companion object {
        const val CATEGORY = "hilt.resolve"
        const val BEGIN = 1
        const val END = 2
    }
}
