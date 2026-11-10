@file:OptIn(DelicateTracingApi::class)

package com.santimattius.android.hilt.coroutines

import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer

/**
 * Runs [block] inside a trace slice that follows the coroutine across suspension points and
 * thread switches.
 *
 * This is a passthrough to `androidx.tracing`: it reads [Tracer.global] on every call, so it is
 * a plain call of [block] when [category] is disabled or no tracing driver is installed. The
 * result and any exception of [block] reach the caller unchanged.
 */
public suspend fun <T> traceCoroutine(
    category: String,
    name: String,
    block: suspend () -> T,
): T = Tracer.global.traceCoroutine(category, name) { block() }
