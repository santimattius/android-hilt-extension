package com.santimattius.android.sample

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
        val gate = { category: String -> (category.startsWith("hilt.") || category == PROBE) && enabled(category) }
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
    private fun captureLaunch(enabled: (String) -> Boolean): Map<Long, List<Node>> {
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
        }
        probe?.closeable?.close()
        traceContext!!.flush()
        return forest(sink.slices()).also { Log.i(TAG, dump(it)) }
    }

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
        val tracks = captureLaunch { true }

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
        val tracks = captureLaunch { it == PROBE }

        assertEquals(listOf(PROBE), tracks.allNodes().map { it.category })
    }

    @Test
    fun enablingOnlyInjectShowsNoCreateSlice() {
        val tracks = captureLaunch { it == INJECT }

        val nodes = tracks.allNodes()
        assertEquals(listOf(INJECT to "MainActivity.members"), nodes.map { it.category to it.name })
        assertTrue(nodes.single().children.isEmpty())
    }

    private companion object {
        const val TAG = "TraceCaptureTest"
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val INJECT = "hilt.inject"
        const val PROBE = "test.probe"
        const val AUDIENCE_SLICE = "kotlin.String@javax.inject.Named(value=audience)"
        const val GREETING_REPOSITORY = "com.santimattius.android.sample.greeting.GreetingRepository"
    }
}
