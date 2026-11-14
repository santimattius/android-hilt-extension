package io.github.santimattius.android.hilt.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compares a call path with Hilt Trace's begin/end around it against the same work without them,
 * while the traced category is disabled (no tracing driver is installed in this process).
 * Compare the two reported medians; the difference is the cost of the disabled-category check.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class DisabledCategoryBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun baseline() {
        assertCategoryDisabled()
        var seed = 0
        benchmarkRule.measureRepeated { seed = CallPaths.baseline(seed + 1) }
        check(seed != Int.MIN_VALUE) // keep the result live
    }

    @Test
    fun instrumentedWithDisabledCategory() {
        assertCategoryDisabled()
        var seed = 0
        benchmarkRule.measureRepeated { seed = CallPaths.instrumented(seed + 1) }
        check(seed != Int.MIN_VALUE)
    }

    private fun assertCategoryDisabled() {
        assertFalse(Tracer.global.isCategoryEnabled(CallPaths.CATEGORY))
    }
}
