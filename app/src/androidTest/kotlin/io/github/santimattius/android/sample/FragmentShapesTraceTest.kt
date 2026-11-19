package io.github.santimattius.android.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.santimattius.android.sample.ShapeSliceRecorder.Companion.INJECT
import io.github.santimattius.android.sample.android.ShapesFragmentHostActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves two Fragment shapes beyond the sample's plain one, on the real Hilt graph: a child fragment of
 * a fragment and a `DialogFragment`. Each injects in its own `onAttach`, so each records one
 * `hilt.inject <Fragment>.members` slice, next to the slice of its parent or host.
 */
@RunWith(AndroidJUnit4::class)
class FragmentShapesTraceTest {

    private val recorder = ShapeSliceRecorder("fragment-shapes-test")

    @After
    fun stopTracing() = recorder.stop()

    @Test
    fun aNestedFragmentAndADialogFragmentEachShowTheirInjectSlice() {
        recorder.start()

        val slices = ActivityScenario.launch(ShapesFragmentHostActivity::class.java).use {
            recorder.awaitSettled { all ->
                EXPECTED.all { name -> all.any { it.type == ShapeSliceRecorder.BEGIN && it.name == name } }
            }
        }

        val all = recorder.nodes(slices, TAG)
        for (name in EXPECTED) {
            assertEquals("one hilt.inject slice for $name: ${all.map { it.name }}", 1, all.count { it.category == INJECT && it.name == name })
        }
    }

    private companion object {
        const val TAG = "FragmentShapesTrace"
        val EXPECTED = listOf(
            "ShapesFragmentHostActivity.members",
            "ShapesParentFragment.members",
            "ShapesChildFragment.members",
            "ShapesDialogFragment.members",
        )
    }
}
