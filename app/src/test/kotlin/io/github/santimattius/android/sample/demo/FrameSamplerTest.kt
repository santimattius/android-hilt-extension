package io.github.santimattius.android.sample.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frames are delivered by hand with a chosen timestamp, as a Choreographer would, so no test sleeps. */
private class FakeFrames : FrameClock {
    override val frameIntervalNanos = 16 * NANOS_PER_MILLI
    private var pending = listOf<(Long) -> Unit>()
    val waiting get() = pending.size

    override fun onNextFrame(callback: (Long) -> Unit) {
        pending = pending + callback
    }

    fun deliver(millis: Long) {
        val callbacks = pending
        pending = emptyList()
        callbacks.forEach { it(millis * NANOS_PER_MILLI) }
    }
}

class FrameSamplerTest {

    private val frames = FakeFrames()
    private val sampler = FrameGapSampler(frames, MonotonicClock { 0L })

    @Test
    fun reportsTheLongestGapAndCountsOnlyGapsOfMoreThanOneAndAHalfFrames() = runTest {
        val stats = async(start = CoroutineStart.UNDISPATCHED) {
            sampler.sample { listOf(16L, 32L, 80L, 96L).forEach(frames::deliver) }
        }
        frames.deliver(112)

        assertEquals(FrameStats(longestGapMillis = 48, jankyFrames = 1, frameIntervalMillis = 16.0), stats.await())
    }

    @Test
    fun countsAStallThatEndsAfterTheWorkIsDone() = runTest {
        // A blocked main thread delivers no frame while the work runs; the next one arrives afterwards.
        val stats = async(start = CoroutineStart.UNDISPATCHED) { sampler.sample { } }
        frames.deliver(300)

        assertEquals(FrameStats(longestGapMillis = 300, jankyFrames = 1, frameIntervalMillis = 16.0), stats.await())
    }

    @Test
    fun stopsRequestingFramesOnceTheWorkIsDone() = runTest {
        val stats = async(start = CoroutineStart.UNDISPATCHED) { sampler.sample { frames.deliver(16) } }
        frames.deliver(32)
        stats.await()

        assertEquals(0, frames.waiting)
    }
}
