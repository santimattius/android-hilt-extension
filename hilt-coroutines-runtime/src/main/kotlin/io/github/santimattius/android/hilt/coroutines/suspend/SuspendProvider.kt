package io.github.santimattius.android.hilt.coroutines.suspend

import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A provider whose [get] suspends instead of blocking the caller.
 *
 * Like [javax.inject.Provider], every call asks the underlying binding again; a scoped binding
 * returns its shared instance and an unscoped one creates a new instance.
 */
public fun interface SuspendProvider<out T> {
    public suspend fun get(): T
}

/**
 * Adapts this [Provider] to a [SuspendProvider] that runs [Provider.get] on [dispatcher].
 *
 * Cancellation is observed before the provider is invoked and after it returns. A `get()` that is
 * already running cannot be interrupted, because [Provider.get] is a blocking call.
 */
public fun <T> Provider<T>.suspending(dispatcher: CoroutineDispatcher): SuspendProvider<T> =
    SuspendProvider { resolveBlockingOn(dispatcher) { get() } }
