package io.github.santimattius.android.sample

import android.app.Application
import android.util.Log
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the Hilt graph of the real debug build of the sample and checks the `hilt.*` slices.
 *
 * What runs for real: the instrumented debug classes of `:app` (the plugin rewrote the generated
 * `SwitchingProvider` and the activity component), the real [SampleApplication] and the real
 * [MainActivity] launched through the framework. What the test drives: it installs the global tracer
 * before each launch and rebuilds the Hilt graph, because the process-wide graph was created by the
 * real application before any test code could install a tracer. The recording sink receives the same
 * events a Perfetto trace would hold; no Perfetto capture is taken.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class TraceCaptureTest {

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

    /** A slice with the slices that opened and closed while it was open. */
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

    private fun install(enabled: (String) -> Boolean) {
        // The tracer's own meta category stays off so no flush slice pollutes the capture.
        val gate = { category: String ->
            (category.startsWith("hilt.") || category == PROBE || category == COROUTINE) && enabled(category)
        }
        val context = TraceContext(sink, true, gate)
        context.createProcessTrack(id = 1, name = "trace-capture-test")
        traceContext = context
        Tracer.setGlobalTracer(PerfettoTracer(context, gate))
    }

    /** Drops the graph the real application built at process start so the next use builds a new one. */
    private fun discardProcessGraph() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)
    }

    /**
     * Launches the real activity on a new graph while a probe slice from outside Hilt wraps the launch,
     * so a capture that shows no `hilt.*` slice still proves the tracer and the sink were live.
     */
    private fun captureLaunch(
        enabled: (String) -> Boolean,
        drive: (MainActivity) -> Unit = {},
        settled: (List<Slice>) -> Boolean = { true },
    ): Map<Long, List<Node>> {
        install(enabled)
        discardProcessGraph()
        val tracer = Tracer.global
        val probe = if (tracer.isCategoryEnabled(PROBE)) {
            tracer.beginSectionWithMetadata(PROBE, "launch", null, false).also { it.metadata.dispatchToTraceSink() }
        } else {
            null
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals("Perfetto", it.greetings.audienceName()) }
            // Close the probe first: the settle loop waits for every begin to have its end.
            probe?.closeable?.close()
            scenario.onActivity(drive)
            awaitSettled(settled)
        }
        traceContext!!.flush()
        return forest(sink.slices()).also { Log.i(TAG, dump(it)) }
    }

    /**
     * Demo actions that run asynchronously (a recomposition, a service, a coroutine) get a bounded wait.
     * The capture counts as settled when the expected slices are there, every begin has its end, and
     * nothing new arrived for [QUIET_MILLIS]: a coroutine closes and reopens its section at each
     * suspension, so a balanced count alone can be a pause in the middle of the action.
     */
    private fun awaitSettled(settled: (List<Slice>) -> Boolean) {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
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
            if (balanced && settled(slices) && now - quietSince >= QUIET_MILLIS * 1_000_000) return
            check(now < deadline) { "the expected slices did not appear within the timeout" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun List<Slice>.hasBegin(category: String, name: String) =
        any { it.type == BEGIN && it.category == category && it.name == name }

    /** Builds the slice tree of every track and fails when begin and end events do not balance. */
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

    @Test
    fun categoriesOnShowCreateSlicesNestedInTheInjectSpan() {
        val tracks = captureLaunch({ true })

        val inject = tracks.allNodes().single { it.category == INJECT }
        assertEquals("MainActivity.members", inject.name)
        // The repository is created inside the span that injects the activity, and the scoped qualified
        // provider it requests is created inside the repository span.
        val repository = inject.children.single { it.name == GREETING_REPOSITORY }
        assertEquals(CREATE, repository.category)
        assertEquals(listOf(CREATE to AUDIENCE_SLICE), repository.children.map { it.category to it.name })
    }

    @Test
    fun categoriesOffShowNoHiltSlice() {
        val tracks = captureLaunch({ it == PROBE })

        assertEquals(listOf(PROBE), tracks.allNodes().map { it.category })
    }

    @Test
    fun enablingOnlyInjectShowsNoCreateSlice() {
        val tracks = captureLaunch({ it == INJECT })

        val nodes = tracks.allNodes()
        assertEquals(listOf(INJECT to "MainActivity.members"), nodes.map { it.category to it.name })
        assertTrue(nodes.single().children.isEmpty())
    }

    @Test
    fun viewModelCreationShowsACreateSlice() {
        val tracks = captureLaunch({ true }, { it.runDemo(DemoId.VIEWMODEL_INJECTION) })

        assertEquals(1, tracks.allNodes().count { it.category == CREATE && it.name == SCRATCH_VIEW_MODEL })
    }

    @Test
    fun viewInjectionShowsAnInjectSlice() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.VIEW_INJECTION) },
            { it.hasBegin(INJECT, "DemoView.members") },
        )

        assertEquals(1, tracks.allNodes().count { it.category == INJECT && it.name == "DemoView.members" })
    }

    @Test
    fun serviceInjectionShowsAnInjectSlice() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.SERVICE_INJECTION) },
            { it.hasBegin(INJECT, "DemoService.members") },
        )

        assertEquals(1, tracks.allNodes().count { it.category == INJECT && it.name == "DemoService.members" })
    }

    @Test
    fun aSingletonIsCreatedOnceNoMatterHowOftenItIsRequested() {
        // Each run requests the binding twice, and the card is run twice: four requests, one creation.
        val tracks = captureLaunch(
            { true },
            { repeat(2) { _ -> it.runDemo(DemoId.SINGLETON_BINDING) } },
            { it.hasBegin(CREATE, SINGLETON_COUNTER) },
        )

        assertEquals(1, tracks.allNodes().count { it.category == CREATE && it.name == SINGLETON_COUNTER })
    }

    @Test
    fun anUnscopedProviderCreatesANewInstanceOnEveryGet() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.SUSPEND_PROVIDER) },
            { slices -> slices.count { it.type == BEGIN && it.name == FRESH_WIDGET } >= 2 },
        )

        assertEquals(2, tracks.allNodes().count { it.category == CREATE && it.name == FRESH_WIDGET })
    }

    @Test
    fun lazyAwaitShowsACreateSliceForTheResolvedBinding() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.LAZY_AWAIT) },
            { it.hasBegin(CREATE, EXPENSIVE_SERVICE) },
        )

        assertEquals(1, tracks.allNodes().count { it.category == CREATE && it.name == EXPENSIVE_SERVICE })
    }

    @Test
    fun complexGraphShowsTheChainNestedTheFanOutAsSiblingsAndTheDeferredCostOutsideResolution() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.COMPLEX_GRAPH) },
            { slices -> slices.count { it.type == BEGIN && it.name == SLOW_SINGLETON } >= 1 },
        )
        val nodes = tracks.allNodes().filter { it.category == CREATE }

        // Deep chain: each level is created inside the previous one. The deferred graph builds the same
        // chain on its first use, so there are two chains and the deferred one is not under its graph.
        val chains = nodes.filter { it.name == "$DEMO_PACKAGE.ChainA" }
        assertEquals(2, chains.size)
        chains.forEach { root ->
            var level = root
            listOf("B", "C", "D", "E").forEach { next ->
                level = level.children.single { it.name == "$DEMO_PACKAGE.Chain$next" }
            }
            assertTrue(level.children.isEmpty())
        }
        // Wide fan-out: the siblings are created one after another inside the consumer, not nested.
        val fanOut = nodes.single { it.name == "$DEMO_PACKAGE.WideFanOut" }
        assertEquals(
            listOf("SlowOne", "SlowTwo", "SlowThree").map { "$DEMO_PACKAGE.$it" },
            fanOut.children.map { it.name },
        )
        assertTrue(fanOut.children.all { it.children.isEmpty() })
        // Deferred: resolving the graph creates nothing under it; the cost shows up elsewhere on first use.
        assertTrue(nodes.single { it.name == "$DEMO_PACKAGE.DeferredGraph" }.children.isEmpty())
        // The slow singleton is created once however often it is requested.
        assertEquals(1, nodes.count { it.name == SLOW_SINGLETON })
    }

    @Test
    fun mainThreadCardCreatesTheChainOnTheMainTrackWhenBlockingAndOnAWorkerTrackWhenSuspending() {
        // A probe slice opened on the main thread names the main thread's track.
        val tracks = captureLaunch(
            { true },
            {
                val tracer = Tracer.global
                tracer.beginSectionWithMetadata(PROBE, "main-thread", null, false).also {
                    it.metadata.dispatchToTraceSink()
                    it.closeable.close()
                }
                it.runDemo(DemoId.COMPLEX_GRAPH_MAIN_THREAD)
            },
            { slices -> slices.count { it.type == BEGIN && it.name == "$DEMO_PACKAGE.ChainA" } >= 2 },
        )
        val mainTrack = tracks.entries.single { (_, roots) -> roots.any { it.name == "main-thread" } }.key
        val onMain = tracks.getValue(mainTrack).flatMap { listOf(it) + it.descendants() }
        val offMain = tracks.filterKeys { it != mainTrack }.allNodes()

        // Both scenarios build the same nested chain and fan-out; only the track differs.
        listOf(onMain, offMain).forEach { nodes ->
            var level = nodes.single { it.name == "$DEMO_PACKAGE.ChainA" }
            listOf("B", "C", "D", "E").forEach { next -> level = level.children.single { it.name == "$DEMO_PACKAGE.Chain$next" } }
            assertEquals(3, nodes.single { it.name == "$DEMO_PACKAGE.WideFanOut" }.children.size)
        }
    }

    @Test
    fun traceCoroutineShowsItsSectionWhenTheCategoryIsEnabled() {
        val tracks = captureLaunch(
            { true },
            { it.runDemo(DemoId.TRACE_COROUTINE) },
            { it.hasBegin(COROUTINE, "refresh") },
        )

        assertTrue(tracks.allNodes().any { it.category == COROUTINE && it.name == "refresh" })
    }

    @Test
    fun traceCoroutineShowsNothingWhenItsCategoryIsDisabled() {
        val tracks = captureLaunch({ it != COROUTINE }, { it.runDemo(DemoId.TRACE_COROUTINE) })

        assertTrue(tracks.allNodes().none { it.category == COROUTINE })
    }

    private companion object {
        const val SETTLE_TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 50L
        const val QUIET_MILLIS = 400L
        const val COROUTINE = "sample.coroutine"
        const val DEMO_PACKAGE = "io.github.santimattius.android.sample.demo"
        const val SCRATCH_VIEW_MODEL = "io.github.santimattius.android.sample.android.ScratchViewModel"
        const val SINGLETON_COUNTER = "$DEMO_PACKAGE.SingletonCounter"
        const val FRESH_WIDGET = "$DEMO_PACKAGE.FreshWidget"
        const val SLOW_SINGLETON = "$DEMO_PACKAGE.SlowSingleton"
        const val EXPENSIVE_SERVICE = "$DEMO_PACKAGE.ExpensiveService"
        const val TAG = "TraceCaptureTest"
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val INJECT = "hilt.inject"
        const val PROBE = "test.probe"
        const val AUDIENCE_SLICE = "kotlin.String@javax.inject.Named(value=audience)"
        const val GREETING_REPOSITORY = "io.github.santimattius.android.sample.greeting.GreetingRepository"
    }
}
