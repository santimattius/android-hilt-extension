package io.github.santimattius.android.hilt.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPathsTest {

    @Test
    fun instrumentedPathReturnsTheSameResultAsTheBaseline() {
        for (seed in listOf(0, 1, 42, Int.MAX_VALUE)) {
            assertEquals(CallPaths.baseline(seed), CallPaths.instrumented(seed))
        }
    }

    @Test
    fun theBenchmarkedCategoryIsDisabledWithoutADriver() {
        // No tracing driver is installed in a unit test, so the category is disabled and
        // begin returns null: the benchmark measures exactly the disabled-category path.
        assertNull(CallPaths.beginForCategoryCheck())
    }
}
