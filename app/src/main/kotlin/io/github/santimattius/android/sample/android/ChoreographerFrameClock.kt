package io.github.santimattius.android.sample.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Display
import io.github.santimattius.android.sample.demo.FrameClock

/**
 * Frame timestamps from the [Choreographer]. [onNextFrame] must be called on the main thread. The
 * interval comes from the refresh rate the display reports when this is created; a variable-rate
 * display may run at another rate, so treat it as a reference, not an exact value.
 */
class ChoreographerFrameClock(context: Context) : FrameClock {

    override val frameIntervalNanos: Long = run {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hertz = display?.refreshRate?.takeIf { it > 0f } ?: DEFAULT_HERTZ
        (NANOS_PER_SECOND / hertz).toLong()
    }

    override fun onNextFrame(callback: (Long) -> Unit) {
        Choreographer.getInstance().postFrameCallback { callback(it) }
    }

    private companion object {
        const val DEFAULT_HERTZ = 60f
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
