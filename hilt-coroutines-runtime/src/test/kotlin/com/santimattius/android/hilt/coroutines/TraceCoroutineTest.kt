package com.santimattius.android.hilt.coroutines

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises the passthrough against a real [PerfettoTracer] whose sink records slice events, the
 * same fixture technique as the facade tests in `:hilt-trace-runtime`.
 */
@OptIn(DelicateTracingApi::class)
class TraceCoroutineTest {

    private class Event(val type: Int, val name: String?)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Event>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) { pooledPacketArray.forEach { recorded += Event(it.type, it.name) } }
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

    /** Installs a recording tracer where only [enabled] categories are active. */
    private fun install(enabled: (String) -> Boolean) {
        val traceContext = TraceContext(sink, true, enabled)
        traceContext.createProcessTrack(id = 1, name = "trace-coroutine-test")
        context = traceContext
        Tracer.setGlobalTracer(PerfettoTracer(traceContext, enabled))
    }

    private fun recorded(): List<Event> {
        context?.flush()
        return sink.slices()
    }

    @Test
    fun returnsTheBlockResultAndRecordsOneNamedSlice() = runTest {
        install { it == CATEGORY }

        val result = traceCoroutine(CATEGORY, "load-user") { "user-1" }

        assertEquals("user-1", result)
        val slices = recorded()
        assertEquals(listOf(BEGIN, END), slices.map { it.type })
        assertEquals("load-user", slices.first().name)
    }

    @Test
    fun disabledCategoryStillRunsTheBlockAndRecordsNothing() = runTest {
        install { false }

        val result = traceCoroutine(CATEGORY, "load-user") { 42 }

        assertEquals(42, result)
        assertTrue(recorded().isEmpty())
    }

    @Test
    fun theBlockCanSuspendAndTheSliceStillCloses() = runTest {
        install { it == CATEGORY }

        val result = traceCoroutine(CATEGORY, "outer") {
            yield()
            traceCoroutine(CATEGORY, "inner") { yield(); "done" }
        }

        assertEquals("done", result)
        // A suspended coroutine is written as several thread segments, so only the invariants of
        // a well-formed trace are asserted: both names appear and every slice is closed.
        val slices = recorded()
        val begunNames = slices.filter { it.type == BEGIN }.mapNotNull { it.name }.toSet()
        assertEquals(setOf("outer", "inner"), begunNames)
        assertEquals(slices.count { it.type == BEGIN }, slices.count { it.type == END })
        assertEquals(0, slices.runningDepth().last())
        assertTrue(slices.runningDepth().all { it >= 0 })
    }

    @Test
    fun anExceptionIsRethrownAndTheSliceIsClosed() = runTest {
        install { it == CATEGORY }

        try {
            traceCoroutine<Unit>(CATEGORY, "failing") { throw IllegalStateException("boom") }
            fail("expected the exception to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }

        assertEquals(listOf(BEGIN, END), recorded().map { it.type })
    }

    private fun List<Event>.runningDepth(): List<Int> =
        runningFold(0) { depth, event -> if (event.type == BEGIN) depth + 1 else depth - 1 }

    private companion object {
        const val CATEGORY = "test.coroutine"
        const val BEGIN = 1
        const val END = 2
    }
}
