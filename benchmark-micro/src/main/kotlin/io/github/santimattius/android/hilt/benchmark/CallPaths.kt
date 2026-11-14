package io.github.santimattius.android.hilt.benchmark

import io.github.santimattius.android.hilt.trace.HiltTrace

/**
 * The two call paths compared by the microbenchmark.
 *
 * [instrumented] has the same shape as the code Hilt Trace generates around a creation case:
 * `begin`, the work, `end` in a `finally`. [baseline] runs the same work with no tracing calls.
 */
object CallPaths {

    const val CATEGORY: String = HiltTrace.CATEGORY_CREATE
    private const val NAME: String = "Benchmark"

    @JvmStatic
    fun baseline(seed: Int): Int = work(seed)

    @JvmStatic
    fun instrumented(seed: Int): Int {
        val token = HiltTrace.begin(CATEGORY, NAME)
        try {
            return work(seed)
        } finally {
            HiltTrace.end(token)
        }
    }

    /** Returns what `begin` returns for the benchmarked category (null when disabled). */
    @JvmStatic
    fun beginForCategoryCheck(): Any? = HiltTrace.begin(CATEGORY, NAME)

    // Small arithmetic so the baseline stays a few instructions.
    private fun work(seed: Int): Int = (seed * 31) xor (seed ushr 7)
}
