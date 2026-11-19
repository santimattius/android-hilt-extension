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
import io.github.santimattius.android.sample.demo.AssistedWidget
import io.github.santimattius.android.sample.demo.AssistedWidgetEntryPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves assisted creation on the real Hilt graph of the sample: building an `@AssistedInject` class
 * through its `@AssistedFactory` records a `hilt.create` slice named after the assisted class, with the
 * slice of its injected dependency nested inside it, and no slice named after the anonymous
 * `$SwitchingProvider$N` factory Hilt generates. Same setup as [TraceCaptureTest]: a recording sink
 * receives the events a Perfetto trace would hold, and the process graph is rebuilt after the tracer is
 * installed. `@HiltWorker` is not covered end to end here: it shares the rule, not this test.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class AssistedCreationTraceTest {

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

    private class Node(val category: String?, val name: String?) {
        val children = mutableListOf<Node>()

        fun descendants(): List<Node> = children.flatMap { listOf(it) + it.descendants() }
        fun render(depth: Int): String = "  ".repeat(depth) + "$category $name\n" + children.joinToString("") { it.render(depth + 1) }
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

    @Test
    fun creatingAnAssistedClassThroughItsFactoryShowsItsCreateSliceWithTheDependencyNestedInside() {
        val gate = { category: String -> category.startsWith("hilt.") }
        val context = TraceContext(sink, true, gate)
        context.createProcessTrack(id = 1, name = "assisted-creation-test")
        traceContext = context
        Tracer.setGlobalTracer(PerfettoTracer(context, gate))
        // The real application built the process graph before any tracer existed: drop it.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)

        val widget = EntryPointAccessors.fromApplication(app, AssistedWidgetEntryPoint::class.java)
            .assistedWidgetFactory()
            .create("label")
        assertEquals("label", widget.label)
        assertEquals("Perfetto", widget.greetings.audienceName())
        awaitStable()
        context.flush()

        val roots = forest(sink.slices())
        Log.i(TAG, roots.entries.joinToString("") { (track, nodes) -> "track $track\n" + nodes.joinToString("") { it.render(1) } })
        val all = roots.values.flatten().flatMap { listOf(it) + it.descendants() }

        val creations = all.filter { it.category == CREATE && it.name == ASSISTED_WIDGET }
        assertEquals("one hilt.create slice for the assisted class", 1, creations.size)
        val inside = creations.single().descendants()
        assertNotNull("GreetingRepository is created inside it", inside.firstOrNull { it.category == CREATE && it.name == GREETING_REPOSITORY })
        assertTrue("no slice is named after the anonymous factory: ${all.map { it.name }}", all.none { it.name!!.contains("SwitchingProvider") })
        assertEquals(AssistedWidget::class.qualifiedName, ASSISTED_WIDGET)
    }

    /** The recording is settled once its begins and ends balance, the assisted slice is there and nothing new arrived for a while. */
    private fun awaitStable() {
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
            val present = slices.any { it.type == BEGIN && it.name == ASSISTED_WIDGET }
            if (balanced && present && now - quietSince >= QUIET_NANOS) return
            check(now < deadline) { "assisted slices did not settle: ${slices.size} slices, balanced=$balanced, present=$present" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun forest(slices: List<Slice>): Map<Long, List<Node>> {
        val roots = mutableMapOf<Long, MutableList<Node>>()
        val open = mutableMapOf<Long, ArrayDeque<Node>>()
        for (slice in slices) {
            val stack = open.getOrPut(slice.track) { ArrayDeque() }
            if (slice.type == BEGIN) {
                val node = Node(slice.category, slice.name)
                val parent = stack.lastOrNull()
                if (parent == null) roots.getOrPut(slice.track) { mutableListOf() } += node else parent.children += node
                stack.addLast(node)
            } else {
                assertTrue("END without BEGIN on track ${slice.track}", stack.isNotEmpty())
                stack.removeLast()
            }
        }
        assertTrue("unclosed slices: ${open.filterValues { it.isNotEmpty() }.keys}", open.values.all { it.isEmpty() })
        return roots
    }

    private companion object {
        const val TAG = "AssistedCreationTrace"
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val POLL_MILLIS = 50L
        const val QUIET_NANOS = 300_000_000L
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val ASSISTED_WIDGET = "io.github.santimattius.android.sample.demo.AssistedWidget"
        const val GREETING_REPOSITORY = "io.github.santimattius.android.sample.greeting.GreetingRepository"
    }
}
