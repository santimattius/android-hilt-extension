package com.santimattius.android.sample.demo

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What a sampling window saw. A gap is the time between two consecutive frames; janky means over 1.5 frames. */
data class FrameStats(val longestGapMillis: Long, val jankyFrames: Int, val frameIntervalMillis: Double)

/** Source of frame timestamps (a Choreographer on a device, delivered by hand in tests). */
interface FrameClock {
    val frameIntervalNanos: Long

    /** Calls [callback] once, with the timestamp of the next frame, on the thread that draws frames. */
    fun onNextFrame(callback: (frameTimeNanos: Long) -> Unit)
}

fun interface FrameSampler {
    /** Runs [block] and reports the frame gaps seen while it ran, plus the first frame after it. */
    suspend fun sample(block: suspend () -> Unit): FrameStats
}

/**
 * Measures gaps between frames. The window starts at [clock]'s now, so a thread blocked from the very
 * beginning still shows its stall at the first frame it delivers; the gap is measured to that frame's
 * timestamp, so it can differ from the real stall by up to one frame.
 */
class FrameGapSampler(private val frames: FrameClock, private val clock: MonotonicClock) : FrameSampler {

    override suspend fun sample(block: suspend () -> Unit): FrameStats {
        val interval = frames.frameIntervalNanos
        var last = clock.nanoTime()
        var longest = 0L
        var janky = 0
        var active = true

        fun record(frameTime: Long) {
            val gap = frameTime - last
            last = frameTime
            longest = maxOf(longest, gap)
            if (gap > interval * JANKY_FRAMES) janky++
        }

        fun tick(): Unit = frames.onNextFrame { frameTime ->
            record(frameTime)
            if (active) tick()
        }

        tick()
        try {
            block()
        } finally {
            active = false
        }
        // A stall that ended with the work only shows at the next frame, so wait for it.
        suspendCancellableCoroutine { continuation ->
            frames.onNextFrame { frameTime ->
                record(frameTime)
                continuation.resume(Unit)
            }
        }
        return FrameStats(longest / NANOS_PER_MILLI, janky, interval.toDouble() / NANOS_PER_MILLI)
    }

    private companion object {
        const val JANKY_FRAMES = 1.5
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
