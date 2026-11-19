@file:OptIn(DelicateTracingApi::class)

package io.github.santimattius.android.hilt.coroutines.suspend

import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import io.github.santimattius.android.hilt.coroutines.traceCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs a blocking Dagger resolution (`Provider.get()`, `Lazy.get()`) on [dispatcher].
 *
 * This is the single place that defines the cancellation contract of the suspend adapters:
 * `withContext` fails fast with [kotlinx.coroutines.CancellationException] when the caller is
 * already cancelled, never starts [resolve] when cancellation arrives before the dispatcher runs
 * it, and rethrows cancellation instead of the result when the caller is cancelled while
 * [resolve] runs. A running `resolve` cannot be interrupted because it is a blocking call.
 * The section [sectionName] wraps the whole call and does not change that contract.
 */
internal suspend inline fun <T> resolveBlockingOn(
    dispatcher: CoroutineDispatcher,
    sectionName: String,
    crossinline resolve: () -> T,
): T =
    // Checked first so a disabled category adds no wrapper allocation on top of the plain call.
    if (Tracer.global.isCategoryEnabled(BLOCKING_RESOLUTION_CATEGORY)) {
        traceCoroutine(BLOCKING_RESOLUTION_CATEGORY, sectionName) { withContext(dispatcher) { resolve() } }
    } else {
        withContext(dispatcher) { resolve() }
    }

/**
 * Category of the section that `Provider.suspending` and `Lazy.await` open around the blocking
 * resolution. `withContext` keeps the trace element, so the section is reopened on the worker thread
 * and a `hilt.create` slice written inside `get()` nests under it.
 */
internal const val BLOCKING_RESOLUTION_CATEGORY: String = "hilt.resolve"
