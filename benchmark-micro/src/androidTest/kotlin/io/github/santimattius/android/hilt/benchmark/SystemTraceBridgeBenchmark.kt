package io.github.santimattius.android.hilt.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tracing.DelicateTracingApi
import androidx.tracing.Trace
import androidx.tracing.Tracer
import io.github.santimattius.android.hilt.trace.HiltTrace
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cost of the opt-in system trace bridge on the traced call path, with the in-process category
 * disabled and no system trace session running:
 *
 * - [bridgeOff]: the default; `begin` pays one extra volatile read.
 * - [bridgeOnWithoutSession]: the bridge is on but `Trace.isEnabled()` is false, so it pays the
 *   platform check on every call and builds no label.
 *
 * The case with a running system trace session (a platform call per section) is not covered: it needs
 * a Perfetto session started around the run, which this module does not do.
 *
 * Compare the medians with `DisabledCategoryBenchmark.baseline`. Only a physical-device run is a
 * measurement; see docs/BENCHMARK.md.
 */
@OptIn(DelicateTracingApi::class)
@RunWith(AndroidJUnit4::class)
class SystemTraceBridgeBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @After
    fun restoreDefault() {
        HiltTrace.setSystemTraceEnabled(false)
    }

    @Test
    fun bridgeOff() {
        HiltTrace.setSystemTraceEnabled(false)
        assertNoTracing()
        var seed = 0
        benchmarkRule.measureRepeated { seed = CallPaths.instrumented(seed + 1) }
        check(seed != Int.MIN_VALUE) // keep the result live
    }

    @Test
    fun bridgeOnWithoutSession() {
        HiltTrace.setSystemTraceEnabled(true)
        assertNoTracing()
        var seed = 0
        benchmarkRule.measureRepeated { seed = CallPaths.instrumented(seed + 1) }
        check(seed != Int.MIN_VALUE)
    }

    private fun assertNoTracing() {
        assertFalse(Tracer.global.isCategoryEnabled(CallPaths.CATEGORY))
        assertFalse("A system trace session is running; the case would measure something else.", Trace.isEnabled())
    }
}
