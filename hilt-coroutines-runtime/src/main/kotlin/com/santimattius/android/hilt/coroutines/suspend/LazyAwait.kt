package com.santimattius.android.hilt.coroutines.suspend

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Resolves this [Lazy] on [dispatcher] without blocking the calling coroutine's thread.
 *
 * The first resolution may be expensive (it runs the binding's construction); caching stays the
 * responsibility of the Dagger [Lazy]. Cancellation is observed before the resolution starts and
 * after it returns; a resolution that is already running cannot be interrupted.
 */
public suspend fun <T> Lazy<T>.await(dispatcher: CoroutineDispatcher = Dispatchers.Default): T =
    resolveBlockingOn(dispatcher) { get() }
