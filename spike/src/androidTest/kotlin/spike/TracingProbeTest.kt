package spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 0.7 probe: no-driver behaviour, closeable type and span nesting for androidx.tracing 2.0.5. */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class TracingProbeTest {
    private class Event(val type: Int, val name: String?, val track: Long)

    private class RecordingSink : AbstractTraceSink() {
        val events = mutableListOf<Event>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(events) {
                pooledPacketArray.forEach { events += Event(it.type, it.name, it.trackUuid) }
            }
            pooledPacketArray.recycle()
        }

        override fun onDroppedTraceEvent() = Unit
        override fun flush() = Unit
        override fun close() = Unit

        fun slices() = synchronized(events) { events.filter { it.type == 1 || it.type == 2 } }
    }

    /** Mirrors the planned facade: begin, dispatch the begin event, keep only the closeable. */
    private fun begin(tracer: Tracer, category: String, name: String): AutoCloseable {
        val holder = tracer.beginSectionWithMetadata(category, name, null, false)
        holder.metadata.dispatchToTraceSink()
        return holder.closeable
    }

    private fun driver(sink: RecordingSink, enabled: (String) -> Boolean) =
        TraceDriver(InstrumentationRegistry.getInstrumentation().targetContext, sink, enabled)

    @Test
    fun noDriverGlobalTracerIsAnInertStub() {
        val tracer = Tracer.global
        assertFalse(tracer.isCategoryEnabled("hilt.create"))
        val closeable = begin(tracer, "hilt.create", "spike.Type")
        closeable.close()
        Log.i("SpikeProbe", "stub tracer=${tracer.javaClass.name} closeable=${closeable.javaClass.name}")
    }

    @Test
    fun enabledCloseableIsAutoCloseableAndSlicesNest() {
        val sink = RecordingSink()
        val driver = driver(sink) { true }
        val tracer = driver.tracer
        val inject = begin(tracer, "hilt.inject", "MainActivity.members")
        val create = begin(tracer, "hilt.create", "spike.Consumer")
        create.close()
        inject.close()
        driver.flush()
        // flush() itself emits a trailing "flush" slice pair; the first four events are ours.
        val slices = sink.slices().take(4)
        Log.i("SpikeProbe", "enabled closeable=${create.javaClass.name} all=" +
            synchronized(sink.events) { sink.events.joinToString { "${it.type}:${it.name}@${it.track}" } })
        assertEquals(listOf(1, 1, 2, 2), slices.map { it.type })
        assertEquals(listOf("MainActivity.members", "spike.Consumer"), slices.take(2).map { it.name })
        assertEquals(1, slices.map { it.track }.distinct().size)
        driver.close()
    }

    @Test
    fun categoriesAreGatedIndependently() {
        val sink = RecordingSink()
        val driver = driver(sink) { it == "hilt.inject" }
        val tracer = driver.tracer
        assertTrue(tracer.isCategoryEnabled("hilt.inject"))
        assertFalse(tracer.isCategoryEnabled("hilt.create"))
        val token = if (tracer.isCategoryEnabled("hilt.create")) begin(tracer, "hilt.create", "x") else null
        assertNull(token)
        begin(tracer, "hilt.inject", "A.members").close()
        driver.flush()
        assertEquals(listOf("A.members"), sink.slices().filter { it.type == 1 }.map { it.name })
        driver.close()
    }
}
