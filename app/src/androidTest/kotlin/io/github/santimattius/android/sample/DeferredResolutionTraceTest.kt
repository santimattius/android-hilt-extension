package io.github.santimattius.android.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PerfettoTracer
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.TraceContext
import androidx.tracing.Tracer
import dagger.hilt.internal.GeneratedComponentManagerHolder
import io.github.santimattius.android.sample.demo.DemoId
import io.github.santimattius.android.sample.demo.DemoRegistry
import io.github.santimattius.android.sample.demo.DemoViewModel
import io.github.santimattius.android.sample.demo.SuspendDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks, on the real Hilt graph of the sample, that the `hilt.create` slice of a deferred binding
 * nests under the section that `Provider.suspending` and `Lazy.await` open around the blocking
 * `get()`.
 *
 * The demos are the real injected ones (`SuspendProviderDemo`, `LazyAwaitDemo`); each calls the helper
 * on a real `Provider<FreshWidget>` / `dagger.Lazy<ExpensiveService>`. The test only chooses the thread
 * of the calling coroutine: the instrumentation thread, or a `Dispatchers.Default` worker. The setup
 * (tracer installed before launch, process graph discarded) is the one of `TraceCaptureTest`.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class DeferredResolutionTraceTest {

    private class Slice(val type: Int, val category: String?, val name: String?, val track: Long)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Slice>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach {
                    recorded += Slice(it.type, it.primaryCategory, it.name, it.trackUuid)
                }
            }
            pooledPacketArray.recycle()
        }

        override fun onDroppedTraceEvent() = Unit
        override fun flush() = Unit
        override fun close() = Unit

        fun slices(): List<Slice> =
            synchronized(recorded) { recorded.filter { it.type == BEGIN || it.type == END } }
    }

    private class Node(val category: String?, val name: String?) {
        val children = mutableListOf<Node>()

        fun descendants(): List<Node> = children.flatMap { listOf(it) + it.descendants() }
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

    private fun install() {
        val gate = { category: String -> category.startsWith("hilt.") }
        val context = TraceContext(sink, true, gate)
        context.createProcessTrack(id = 1, name = "deferred-resolution-test")
        traceContext = context
        Tracer.setGlobalTracer(PerfettoTracer(context, gate))
    }

    private fun discardProcessGraph() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)
    }

    /**
     * Runs the demo of [id] with its coroutine started in [callerContext] and returns the slice tree of
     * every track. The registry is read from the real [DemoViewModel], which Hilt built with it.
     */
    private fun capture(id: DemoId, callerContext: CoroutineContext): Map<Long, List<Node>> {
        install()
        discardProcessGraph()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var demo: SuspendDemo? = null
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[DemoViewModel::class.java]
                val registry = viewModel.javaClass.getDeclaredField("registry")
                    .apply { isAccessible = true }.get(viewModel) as DemoRegistry
                demo = registry.find(id)
            }
            // Slices that earlier launch work still has to write must not be mistaken for this run's.
            val before = sink.slices().size
            runBlocking(callerContext) { demo!!.run() }
            val raw = awaitBalanced()
            Log.i(TAG, "raw events:\n" + raw.joinToString("\n") { "${if (it.type == BEGIN) "B" else "E"} ${it.track} ${it.category} ${it.name}" })
            val tracks = forest(raw)
            Log.i(TAG, "slices before the run: $before\n" + dump(tracks))
            return tracks
        }
    }

    /**
     * Flushes until every BEGIN has its END and the count stopped moving. Events written by a worker
     * thread can reach the sink after `runBlocking` returned, so a single flush may see a section half written.
     */
    private fun awaitBalanced(): List<Slice> {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        var lastCount = -1
        var quietSince = System.nanoTime()
        while (true) {
            traceContext!!.flush()
            val slices = sink.slices()
            val now = System.nanoTime()
            if (slices.size != lastCount) {
                lastCount = slices.size
                quietSince = now
            }
            val balanced = slices.count { it.type == BEGIN } == slices.count { it.type == END }
            if (balanced && now - quietSince >= QUIET_NANOS) return slices
            if (now > deadline) return slices
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

    private fun dump(tracks: Map<Long, List<Node>>): String {
        fun Node.render(depth: Int): String =
            "  ".repeat(depth) + "$category $name\n" + children.joinToString("") { it.render(depth + 1) }
        return tracks.entries.joinToString("") { (track, roots) ->
            "track $track\n" + roots.joinToString("") { it.render(1) }
        }
    }

    private fun Map<Long, List<Node>>.allNodes(): List<Node> =
        values.flatten().flatMap { listOf(it) + it.descendants() }

    /** Every `hilt.create` slice named [binding] sits below a [section] slice, which is on its own track. */
    private fun assertCreatedUnder(tracks: Map<Long, List<Node>>, section: String, binding: String, expected: Int) {
        val sections = tracks.allNodes().filter { it.category == RESOLVE && it.name == section }
        assertTrue("no $section section was recorded", sections.isNotEmpty())
        val nested = sections.flatMap { it.descendants() }.filter { it.category == CREATE && it.name == binding }
        val all = tracks.allNodes().filter { it.category == CREATE && it.name == binding }
        assertEquals("every $binding creation is expected inside a $section section", all.size, nested.size)
        assertEquals(expected, nested.size)
    }

    @Test
    fun providerSuspendingNestsTheCreateSliceUnderItsSectionWhenCalledFromTheTestThread() {
        val tracks = capture(DemoId.SUSPEND_PROVIDER, EmptyCoroutineContext)

        assertCreatedUnder(tracks, "Provider.suspending", FRESH_WIDGET, expected = 2)
    }

    @Test
    fun providerSuspendingNestsTheCreateSliceUnderItsSectionWhenCalledFromAWorker() {
        val tracks = capture(DemoId.SUSPEND_PROVIDER, Dispatchers.Default)

        assertCreatedUnder(tracks, "Provider.suspending", FRESH_WIDGET, expected = 2)
    }

    @Test
    fun lazyAwaitNestsTheCreateSliceUnderItsSectionWhenCalledFromTheTestThread() {
        val tracks = capture(DemoId.LAZY_AWAIT, EmptyCoroutineContext)

        assertCreatedUnder(tracks, "Lazy.await", EXPENSIVE_SERVICE, expected = 1)
    }

    @Test
    fun lazyAwaitNestsTheCreateSliceUnderItsSectionWhenCalledFromAWorker() {
        val tracks = capture(DemoId.LAZY_AWAIT, Dispatchers.Default)

        assertCreatedUnder(tracks, "Lazy.await", EXPENSIVE_SERVICE, expected = 1)
    }

    private companion object {
        const val TAG = "DeferredResolutionTest"
        const val DEMO_PACKAGE = "io.github.santimattius.android.sample.demo"
        const val FRESH_WIDGET = "$DEMO_PACKAGE.FreshWidget"
        const val EXPENSIVE_SERVICE = "$DEMO_PACKAGE.ExpensiveService"
        const val SETTLE_TIMEOUT_NANOS = 5_000_000_000L
        const val QUIET_NANOS = 200_000_000L
        const val POLL_MILLIS = 25L
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val RESOLVE = "hilt.resolve"
    }
}
