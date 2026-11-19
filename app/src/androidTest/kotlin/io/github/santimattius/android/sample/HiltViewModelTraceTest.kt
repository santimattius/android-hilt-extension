package io.github.santimattius.android.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.CREATE
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.GREETING_REPOSITORY
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.INJECT
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.SAMPLE_ANDROID
import io.github.santimattius.android.sample.android.ShapesComposeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves `hiltViewModel()` from a Compose screen on the real Hilt graph of the sample: a plain
 * `@HiltViewModel` and one that takes a `SavedStateHandle` each record a `hilt.create` slice named after
 * the ViewModel class, and the host `@AndroidEntryPoint` activity records its `hilt.inject` slice.
 */
@RunWith(AndroidJUnit4::class)
class HiltViewModelTraceTest {

    private val recorder = ShapeSliceRecorder("hilt-view-model-test")

    @After
    fun stopTracing() = recorder.stop()

    @Test
    fun hiltViewModelFromComposeShowsOneCreateSlicePerViewModelAndTheHostInjectSlice() {
        recorder.start()

        val slices = ActivityScenario.launch(ShapesComposeActivity::class.java).use {
            recorder.awaitSettled { all ->
                listOf(HOST, PLAIN, SAVED_STATE).all { name -> all.any { it.type == ShapeSliceRecorder.BEGIN && it.name == name } }
            }
        }

        val all = recorder.nodes(slices, TAG)
        val host = all.filter { it.category == INJECT && it.name == HOST }
        assertEquals("one hilt.inject slice for the Compose activity: ${all.map { it.name }}", 1, host.size)
        assertNotNull(
            "GreetingRepository is created inside the activity's inject slice",
            host.single().descendants().firstOrNull { it.category == CREATE && it.name == GREETING_REPOSITORY },
        )
        assertEquals("one hilt.create slice for the plain ViewModel", 1, all.count { it.category == CREATE && it.name == PLAIN })
        assertEquals(
            "one hilt.create slice for the SavedStateHandle ViewModel",
            1,
            all.count { it.category == CREATE && it.name == SAVED_STATE },
        )
    }

    private companion object {
        const val TAG = "HiltViewModelTrace"
        const val HOST = "ShapesComposeActivity.members"
        const val PLAIN = "$SAMPLE_ANDROID.PlainShapeViewModel"
        const val SAVED_STATE = "$SAMPLE_ANDROID.SavedStateShapeViewModel"
    }
}
