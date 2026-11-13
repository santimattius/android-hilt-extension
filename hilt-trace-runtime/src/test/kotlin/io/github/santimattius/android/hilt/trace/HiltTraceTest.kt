package io.github.santimattius.android.hilt.trace

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import java.lang.management.ManagementFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the facade against a real [PerfettoTracer] whose sink records slice events, so the
 * assertions see exactly what a Perfetto trace would contain.
 */
@OptIn(DelicateTracingApi::class)
class HiltTraceTest {

    private class Event(val type: Int, val name: String?, val track: Long)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Event>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach { recorded += Event(it.type, it.name, it.trackUuid) }
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

    /**
     * Installs a recording tracer where only the `hilt.*` categories accepted by [enabled] are
     * active. The tracer's own meta category stays off so `flush()` adds no slices of its own.
     */
    private fun install(enabled: (String) -> Boolean) {
        val gate = { category: String -> category.startsWith("hilt.") && enabled(category) }
        val traceContext = TraceContext(sink, true, gate)
        traceContext.createProcessTrack(id = 1, name = "hilt-trace-test")
        context = traceContext
        Tracer.setGlobalTracer(PerfettoTracer(traceContext, gate))
    }

    private fun recorded(): List<Event> {
        context?.flush()
        return sink.slices()
    }

    @Test
    fun beginEmitsBeginEventBeforeEnd() {
        install { true }

        val token = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "com.x.Foo")
        HiltTrace.end(token)

        val slices = recorded()
        assertEquals(listOf(BEGIN, END), slices.map { it.type })
        assertEquals("com.x.Foo", slices.first().name)
    }

    @Test
    fun beginWithoutDispatchWouldHideTheBeginEvent() {
        // Without `metadata.dispatchToTraceSink()` the sink only sees END (observed `2,2`).
        install { true }

        HiltTrace.end(HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "A.members"))
        HiltTrace.end(HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "B"))

        assertEquals(listOf(BEGIN, END, BEGIN, END), recorded().map { it.type })
    }

    @Test
    fun beginAndEndAreStaticMethodsCallableFromBytecode() {
        val begin = HiltTrace::class.java.getMethod("begin", String::class.java, String::class.java)
        val end = HiltTrace::class.java.getMethod("end", Any::class.java)

        assertTrue(java.lang.reflect.Modifier.isStatic(begin.modifiers))
        assertTrue(java.lang.reflect.Modifier.isStatic(end.modifiers))
        assertEquals(Any::class.java, begin.returnType)
        assertEquals(Void.TYPE, end.returnType)
    }

    @Test
    fun beginReturnsAnAutoCloseableToken() {
        install { true }

        val token = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "Foo")

        assertTrue(token is AutoCloseable)
        HiltTrace.end(token)
    }

    @Test
    fun nestedSpansCloseInLifoOrderOnOneTrack() {
        install { true }

        val inject = HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "MainActivity.members")
        val create = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "com.x.Consumer")
        HiltTrace.end(create)
        HiltTrace.end(inject)

        val slices = recorded()
        assertEquals(listOf(BEGIN, BEGIN, END, END), slices.map { it.type })
        assertEquals(
            listOf("MainActivity.members", "com.x.Consumer"),
            slices.take(2).map { it.name },
        )
        assertEquals(1, slices.map { it.track }.distinct().size)
    }

    @Test
    fun disabledCategoryReturnsNullTokenAndEmitsNothing() {
        install { false }

        val token = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "Foo")
        HiltTrace.end(token)

        assertNull(token)
        assertTrue(recorded().isEmpty())
    }

    @Test
    fun disabledCategoryAllocatesNothing() {
        install { false }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        fun burst() {
            repeat(CALLS) { HiltTrace.end(HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "Foo")) }
        }

        burst() // warm up the measurement path and the facade
        val before = threads.getThreadAllocatedBytes(id)
        burst()
        val allocated = threads.getThreadAllocatedBytes(id) - before

        // A disabled category must not allocate per call: one object per call would add at least
        // CALLS * 16 bytes. The bound only absorbs one-off noise of the measurement itself (JIT and
        // thread-local allocation buffers differ between JDKs and machines).
        assertTrue(
            "allocated $allocated bytes over $CALLS disabled calls",
            allocated < MAX_NOISE_BYTES,
        )
    }

    @Test
    fun enabledCategoryEmitsOnEveryCall() {
        install { true }

        repeat(3) { HiltTrace.end(HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "Foo")) }

        assertEquals(6, recorded().size)
    }

    @Test
    fun onlyTheEnabledCategoryEmits() {
        install { it == HiltTrace.CATEGORY_CREATE }

        val inject = HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "A.members")
        val create = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "com.x.B")
        HiltTrace.end(create)
        HiltTrace.end(inject)

        assertNull(inject)
        assertNotNull(create)
        val slices = recorded()
        assertEquals(listOf(BEGIN, END), slices.map { it.type })
        assertEquals("com.x.B", slices.first().name)
    }

    @Test
    fun injectCategoryAloneEmitsOnlyInjectSlices() {
        install { it == HiltTrace.CATEGORY_INJECT }

        val inject = HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "A.members")
        val create = HiltTrace.begin(HiltTrace.CATEGORY_CREATE, "com.x.B")
        HiltTrace.end(create)
        HiltTrace.end(inject)

        assertNotNull(inject)
        assertNull(create)
        assertEquals("A.members", recorded().first().name)
        assertEquals(2, recorded().size)
    }

    @Test
    fun withoutDriverTheStubTracerIsInert() {
        // No install(): the global tracer is the stub.
        val stub = Tracer.global
        assertFalse(stub.isCategoryEnabled(HiltTrace.CATEGORY_CREATE))
        val holder = stub.beginSectionWithMetadata(HiltTrace.CATEGORY_CREATE, "Foo", null, false)
        holder.closeable.close()
        assertEquals("EmptyCloseable", holder.closeable.javaClass.simpleName)
    }

    @Test
    fun withoutDriverBeginAndEndDoNotThrow() {
        val token = HiltTrace.begin(HiltTrace.CATEGORY_INJECT, "Foo")
        HiltTrace.end(token)

        assertNull(token)
    }

    @Test
    fun sliceNameCarriesTheBindingTypeAlone() {
        assertEquals("com.x.Foo", HiltTrace.sliceName("com.x.Foo", null))
    }

    @Test
    fun sliceNameAppendsTheQualifier() {
        assertEquals("com.x.Foo@javax.inject.Named", HiltTrace.sliceName("com.x.Foo", "javax.inject.Named"))
    }

    @Test
    fun sliceNameTreatsABlankQualifierAsAbsent() {
        assertEquals("com.x.Foo", HiltTrace.sliceName("com.x.Foo", ""))
    }

    @Test
    fun sliceNameReachesTheTraceAsTheSliceName() {
        install { true }

        HiltTrace.end(
            HiltTrace.begin(HiltTrace.CATEGORY_CREATE, HiltTrace.sliceName("com.x.Foo", "Io")),
        )

        assertEquals("com.x.Foo@Io", recorded().first().name)
    }

    private companion object {
        const val BEGIN = 1
        const val END = 2
        const val CALLS = 10_000
        const val MAX_NOISE_BYTES = 4_096L
    }
}
