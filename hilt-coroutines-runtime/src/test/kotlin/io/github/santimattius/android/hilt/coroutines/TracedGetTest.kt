@file:OptIn(DelicateTracingApi::class)

package io.github.santimattius.android.hilt.coroutines

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import dagger.Lazy
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `Provider.getTraced` and `Lazy.getTraced` open a `hilt.resolve` section named by the caller around
 * a blocking `get()` on the same thread. The fixture is the recording [PerfettoTracer] that the
 * other trace tests of this module use.
 */
class TracedGetTest {

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
        traceContext.createProcessTrack(id = 1, name = "traced-get-test")
        context = traceContext
        Tracer.setGlobalTracer(PerfettoTracer(traceContext, enabled))
    }

    private fun recorded(): List<Event> {
        context?.flush()
        return sink.slices()
    }

    @Test
    fun providerGetTracedReturnsTheValueAndOpensANamedSectionAroundGet() {
        install { it == CATEGORY }
        var openDuringGet: List<String?> = emptyList()
        val provider = Provider {
            openDuringGet = recorded().also { assertEquals(0, it.count { e -> e.type == END }) }
                .filter { it.type == BEGIN }.map { it.name }
            "value"
        }

        val result = provider.getTraced("CheckoutScreen")

        assertEquals("value", result)
        assertEquals(listOf<String?>("CheckoutScreen"), openDuringGet)
        val slices = recorded()
        assertEquals(listOf(BEGIN, END), slices.map { it.type })
        assertEquals(CATEGORY, slices.first().category)
        assertEquals("CheckoutScreen", slices.first().name)
    }

    @Test
    fun lazyGetTracedReturnsTheValueAndOpensANamedSectionAroundGet() {
        install { it == CATEGORY }
        var openDuringGet: List<String?> = emptyList()
        val lazy = Lazy {
            openDuringGet = recorded().filter { it.type == BEGIN }.map { it.name }
            "value"
        }

        val result = lazy.getTraced("ProfileScreen")

        assertEquals("value", result)
        assertEquals(listOf<String?>("ProfileScreen"), openDuringGet)
        assertEquals(listOf(BEGIN, END), recorded().map { it.type })
    }

    @Test
    fun anExceptionFromGetPropagatesAndTheSectionCloses() {
        install { it == CATEGORY }

        try {
            Provider<String> { throw IllegalStateException("boom") }.getTraced("failing")
            fail("expected the exception to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
        try {
            Lazy<String> { throw IllegalStateException("boom") }.getTraced("failing")
            fail("expected the exception to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }

        assertEquals(listOf(BEGIN, END, BEGIN, END), recorded().map { it.type })
    }

    @Test
    fun withTheCategoryDisabledTheValueIsReturnedAndNothingIsRecorded() {
        install { false }

        assertEquals(1, Provider { 1 }.getTraced("x"))
        assertEquals(2, Lazy { 2 }.getTraced("x"))

        assertTrue(recorded().isEmpty())
    }

    @Test
    fun withNoTracerInstalledTheValueIsReturned() {
        assertEquals(1, Provider { 1 }.getTraced("x"))
        assertEquals(2, Lazy { 2 }.getTraced("x"))
    }

    @Test
    fun aFailingTracerNeverBreaksGet() {
        install { if (it == CATEGORY) throw IllegalStateException("tracer down") else false }

        assertEquals("value", Provider { "value" }.getTraced("x"))
        assertEquals("value", Lazy { "value" }.getTraced("x"))
    }

    private companion object {
        const val CATEGORY = "hilt.resolve"
        const val BEGIN = 1
        const val END = 2
    }
}
