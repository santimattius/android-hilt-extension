package io.github.santimattius.android.sample

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import dagger.hilt.internal.GeneratedComponentManagerHolder
import org.junit.Assert.assertTrue

/** One recorded slice event: BEGIN or END, with its category, name and track. */
class ShapeSlice(val type: Int, val category: String?, val name: String?, val track: Long)

/** A slice with the slices that opened and closed inside it on the same track. */
class ShapeNode(val category: String?, val name: String?) {
    val children = mutableListOf<ShapeNode>()

    fun descendants(): List<ShapeNode> = children.flatMap { listOf(it) + it.descendants() }
    fun render(depth: Int): String =
        "  ".repeat(depth) + "$category $name\n" + children.joinToString("") { it.render(depth + 1) }
}

/**
 * Records the slices a Perfetto trace would hold, for the injection-shape tests. [start] installs the
 * global tracer and drops the process graph the real application built before any tracer existed;
 * [awaitSettled] waits until begin and end counts are equal and stable.
 */
@OptIn(DelicateTracingApi::class)
class ShapeSliceRecorder(private val label: String) {

    private class Sink : AbstractTraceSink() {
        private val recorded = mutableListOf<ShapeSlice>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach { recorded += ShapeSlice(it.type, it.primaryCategory, it.name, it.trackUuid) }
            }
            pooledPacketArray.recycle()
        }

        override fun onDroppedTraceEvent() = Unit
        override fun flush() = Unit
        override fun close() = Unit

        fun slices(): List<ShapeSlice> =
            synchronized(recorded) { recorded.filter { it.type == BEGIN || it.type == END } }
    }

    private val sink = Sink()
    private var context: TraceContext? = null

    fun start(): Application {
        Tracer.resetGlobalTracer()
        val gate = { category: String -> category.startsWith("hilt.") }
        val traceContext = TraceContext(sink, true, gate)
        traceContext.createProcessTrack(id = 1, name = label)
        context = traceContext
        Tracer.setGlobalTracer(PerfettoTracer(traceContext, gate))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)
        return app
    }

    fun stop() {
        Tracer.resetGlobalTracer()
        context?.close()
    }

    /** Waits until [present] holds, begins and ends balance, and nothing new arrived for a while. */
    fun awaitSettled(present: (List<ShapeSlice>) -> Boolean): List<ShapeSlice> {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        var lastCount = -1
        var quietSince = System.nanoTime()
        while (true) {
            context!!.flush()
            val slices = sink.slices()
            val now = System.nanoTime()
            if (slices.size != lastCount) {
                lastCount = slices.size
                quietSince = now
            }
            val balanced = slices.count { it.type == BEGIN } == slices.count { it.type == END }
            val ready = present(slices)
            if (balanced && ready && now - quietSince >= QUIET_NANOS) return slices
            check(now < deadline) { "slices did not settle: ${slices.size} slices, balanced=$balanced, present=$ready" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Rebuilds the nesting per track, logs it under [tag] and returns every node. */
    fun nodes(slices: List<ShapeSlice>, tag: String): List<ShapeNode> {
        val roots = mutableMapOf<Long, MutableList<ShapeNode>>()
        val open = mutableMapOf<Long, ArrayDeque<ShapeNode>>()
        for (slice in slices) {
            val stack = open.getOrPut(slice.track) { ArrayDeque() }
            if (slice.type == BEGIN) {
                val node = ShapeNode(slice.category, slice.name)
                val parent = stack.lastOrNull()
                if (parent == null) roots.getOrPut(slice.track) { mutableListOf() } += node else parent.children += node
                stack.addLast(node)
            } else {
                assertTrue("END without BEGIN on track ${slice.track}", stack.isNotEmpty())
                stack.removeLast()
            }
        }
        assertTrue("unclosed slices: ${open.filterValues { it.isNotEmpty() }.keys}", open.values.all { it.isEmpty() })
        Log.i(tag, roots.entries.joinToString("") { (track, nodes) -> "track $track\n" + nodes.joinToString("") { it.render(1) } })
        return roots.values.flatten().flatMap { listOf(it) + it.descendants() }
    }

    companion object {
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val INJECT = "hilt.inject"
        const val POLL_MILLIS = 50L
        const val QUIET_NANOS = 300_000_000L
        const val TIMEOUT_NANOS = 15_000_000_000L
        const val GREETING_REPOSITORY = "io.github.santimattius.android.sample.greeting.GreetingRepository"
        const val SAMPLE_ANDROID = "io.github.santimattius.android.sample.android"
    }
}
