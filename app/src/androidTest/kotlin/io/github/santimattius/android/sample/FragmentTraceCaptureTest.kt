package io.github.santimattius.android.sample

import android.app.Application
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
import io.github.santimattius.android.sample.android.DemoFragment
import io.github.santimattius.android.sample.android.FragmentHostActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the Fragment trace path end to end on the real debug build: a real `@AndroidEntryPoint`
 * fragment attached to a real `@AndroidEntryPoint` activity is injected by Hilt, and the recorded slices
 * hold its `hilt.inject` section with the creation of the injected binding nested inside it.
 * Same setup as [TraceCaptureTest]: a recording sink receives the events a Perfetto trace would hold.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class FragmentTraceCaptureTest {

    private class Slice(val type: Int, val category: String?, val name: String?)

    private class RecordingSink : AbstractTraceSink() {
        private val recorded = mutableListOf<Slice>()

        override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
            synchronized(recorded) {
                pooledPacketArray.forEach { recorded += Slice(it.type, it.primaryCategory, it.name) }
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

    @Test
    fun aHiltInjectedFragmentShowsAnInjectSliceWithItsBindingCreatedInside() {
        val gate = { category: String -> category.startsWith("hilt.") }
        val context = TraceContext(sink, true, gate)
        context.createProcessTrack(id = 1, name = "fragment-trace-test")
        traceContext = context
        Tracer.setGlobalTracer(PerfettoTracer(context, gate))
        // The real application built the process graph before any tracer existed: drop it.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = (app as GeneratedComponentManagerHolder).componentManager()
        manager.javaClass.getDeclaredField("component").apply { isAccessible = true }.set(manager, null)

        ActivityScenario.launch(FragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag(FragmentHostActivity.TAG)
                assertTrue("the fragment is attached", fragment is DemoFragment)
                assertEquals("Perfetto", (fragment as DemoFragment).greetings.audienceName())
            }
            awaitSlices()
        }
        context.flush()

        val slices = sink.slices()
        assertEquals("balanced begin/end", slices.count { it.type == BEGIN }, slices.count { it.type == END })
        // The fragment's section opens, the repository is created inside it, and it closes after.
        val begin = slices.indexOfFirst { it.type == BEGIN && it.category == INJECT && it.name == "DemoFragment.members" }
        assertTrue("hilt.inject DemoFragment.members was recorded", begin >= 0)
        assertEquals(1, slices.count { it.type == BEGIN && it.name == "DemoFragment.members" })
        var depth = 0
        var createdInside = false
        for (slice in slices.drop(begin)) {
            if (slice.type == BEGIN) {
                depth++
                if (depth > 1 && slice.category == CREATE && slice.name == GREETING_REPOSITORY) createdInside = true
            } else {
                depth--
            }
            if (depth == 0) break
        }
        assertTrue("GreetingRepository is created inside the fragment's inject slice", createdInside)
    }

    private fun awaitSlices() {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (true) {
            traceContext!!.flush()
            if (sink.slices().any { it.name == "DemoFragment.members" }) return
            check(System.nanoTime() < deadline) { "DemoFragment.members did not appear within the timeout" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val BEGIN = 1
        const val END = 2
        const val CREATE = "hilt.create"
        const val INJECT = "hilt.inject"
        const val GREETING_REPOSITORY = "io.github.santimattius.android.sample.greeting.GreetingRepository"
    }
}
