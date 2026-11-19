package io.github.santimattius.android.sample

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.CREATE
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.GREETING_REPOSITORY
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.INJECT
import io.github.santimattius.android.sample.android.ShapeReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves an `@AndroidEntryPoint` BroadcastReceiver on the real Hilt graph of the sample: a real
 * `sendBroadcast` to the manifest-registered receiver makes `Hilt_ShapeReceiver.onReceive` inject it,
 * which records `hilt.inject ShapeReceiver.members` with the creation of its repository nested inside.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastReceiverTraceTest {

    private val recorder = ShapeSliceRecorder("broadcast-receiver-test")

    @After
    fun stopTracing() = recorder.stop()

    @Test
    fun aBroadcastReceiverShowsItsInjectSliceWithTheRepositoryCreatedInside() {
        val app = recorder.start()

        app.sendBroadcast(Intent().setComponent(ComponentName(app, ShapeReceiver::class.java)))
        val slices = recorder.awaitSettled { all -> all.any { it.type == ShapeSliceRecorder.BEGIN && it.name == RECEIVER } }

        val all = recorder.nodes(slices, TAG)
        val injections = all.filter { it.category == INJECT && it.name == RECEIVER }
        assertEquals("one hilt.inject slice for the receiver: ${all.map { it.name }}", 1, injections.size)
        assertNotNull(
            "GreetingRepository is created inside it",
            injections.single().descendants().firstOrNull { it.category == CREATE && it.name == GREETING_REPOSITORY },
        )
    }

    private companion object {
        const val TAG = "BroadcastReceiverTrace"
        const val RECEIVER = "ShapeReceiver.members"
    }
}
