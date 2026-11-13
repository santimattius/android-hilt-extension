package io.github.santimattius.android.hilt.coroutines.suspend

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
 */
internal suspend inline fun <T> resolveBlockingOn(
    dispatcher: CoroutineDispatcher,
    crossinline resolve: () -> T,
): T = withContext(dispatcher) { resolve() }
