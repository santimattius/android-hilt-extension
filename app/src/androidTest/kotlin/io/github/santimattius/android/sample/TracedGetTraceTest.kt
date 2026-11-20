package io.github.santimattius.android.sample

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.internal.GeneratedComponentManagerHolder
import io.github.santimattius.android.hilt.coroutines.getTraced
import io.github.santimattius.android.sample.demo.TracedGetEntryPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves on the real Hilt graph of the sample that `Provider.getTraced` and `Lazy.getTraced` (issue #84)
 * link a plain blocking `get()` to the caller: the `hilt.create` slice of the binding sits strictly
 * between the BEGIN and END of the `hilt.resolve` section named by the caller, on the same track.
 * Same setup as [MembersInjectorTraceTest]: a recording sink receives the events a Perfetto trace would
 * hold, and the process graph is rebuilt after the tracer is installed. The binding is reached through
 * [TracedGetEntryPoint].
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class TracedGetTraceTest {

    private class Slice(val type: Int, val category: String?, val name: String?, val track: Long)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Slice>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach { recorded += Slice(it.type, it.primaryCategory, it.name, it.trackUuid) }
            }
            pooledPacketArray.recycle()
        }

        override fun onDroppedTraceEvent() = Unit
        override fun flush() = Unit
        override fun close() = Unit

        fun slices(): List<Slice> = synchronized(recorded) { recorded.filter { it.type == BEGIN || it.type == END } }
    }

    private val sink = RecordingSink()
    private var traceContext: TraceContext? = null

    @Before
    fun startWithoutTracer() {
        Tracer.resetGlobalTracer()
    }

    @After
    fun stopTracing() {
        Tracer.resetGlobalTracer()
        traceContext?.close()
    }

    /** Installs the tracer, rebuilds the process graph and returns the entry point of the new graph. */
    private fun entryPoint(): TracedGetEntryPoint {
        val gate = { category: String -> category.startsWith("hilt.") }
        val context = TraceContext(sink, true, gate)
        context.createProcessTrack(id = 1, name = "traced-get-test")
        traceContext = context
        Tracer.setGlobalTracer(PerfettoTracer(context, gate))
        // The real application built the process graph before any tracer existed: drop it.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)
        return EntryPointAccessors.fromApplication(app, TracedGetEntryPoint::class.java)
    }

    @Test
    fun providerGetTracedNestsTheCreateSliceBetweenTheBeginAndEndOfItsSection() {
        val entryPoint = entryPoint()

        val widget = entryPoint.freshWidget().getTraced(SECTION)

        assertTrue(widget.id > 0)
        assertNestedOnOneTrack(FRESH_WIDGET)
    }

    @Test
    fun lazyGetTracedNestsTheCreateSliceBetweenTheBeginAndEndOfItsSection() {
        val entryPoint = entryPoint()

        val service = entryPoint.expensiveService().getTraced(SECTION)

        assertTrue(service.createdOn.isNotEmpty())
        assertNestedOnOneTrack(EXPENSIVE_SERVICE)
    }

    /**
     * Settles the recording, then checks the raw event order: on one track the events of [binding] are
     * `BEGIN hilt.resolve SECTION`, `BEGIN hilt.create binding`, `END`, `END`.
     */
    private fun assertNestedOnOneTrack(binding: String) {
        val slices = awaitStable(binding)
        Log.i(TAG, "events:\n" + slices.joinToString("\n") { "${if (it.type == BEGIN) "B" else "E"} ${it.track} ${it.category} ${it.name}" })
        val begin = slices.single { it.type == BEGIN && it.category == RESOLVE && it.name == SECTION }
        val create = slices.single { it.type == BEGIN && it.category == CREATE && it.name == binding }
        assertEquals("the section and the creation are on one track", begin.track, create.track)
        val onTrack = slices.filter { it.track == begin.track }
        val window = onTrack.drop(onTrack.indexOf(begin)).take(4)
        // END events carry no name or category: on one track they close the innermost open slice.
        assertEquals("BEGIN, BEGIN, END, END", listOf(BEGIN, BEGIN, END, END), window.map { it.type })
        assertEquals(create, window[1])
    }

    /** Flushes until begins and ends balance, [binding] was created and nothing new arrived for a while. */
    private fun awaitStable(binding: String): List<Slice> {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        var lastCount = -1
        var quietSince = 0L
        while (true) {
            traceContext!!.flush()
            val slices = sink.slices()
            val balanced = slices.count { it.type == BEGIN } == slices.count { it.type == END }
            val now = System.nanoTime()
            if (slices.size != lastCount) {
                lastCount = slices.size
                quietSince = now
            }
            val present = slices.any { it.type == BEGIN && it.category == CREATE && it.name == binding }
            if (balanced && present && now - quietSince >= QUIET_NANOS) return slices
            check(now < deadline) { "slices did not settle: ${slices.size} slices, balanced=$balanced, present=$present" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TAG = "TracedGetTrace"
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val RESOLVE = "hilt.resolve"
        const val SECTION = "CheckoutScreen"
        const val DEMO_PACKAGE = "io.github.santimattius.android.sample.demo"
        const val FRESH_WIDGET = "$DEMO_PACKAGE.FreshWidget"
        const val EXPENSIVE_SERVICE = "$DEMO_PACKAGE.ExpensiveService"
        const val POLL_MILLIS = 50L
        const val QUIET_NANOS = 300_000_000L
        const val TIMEOUT_NANOS = 10_000_000_000L
    }
}
