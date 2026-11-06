package com.santimattius.android.hilt.coroutines.suspend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lazily resolved value whose initializer may suspend.
 *
 * Resolution is mutex-guarded: concurrent callers wait for the one that holds the lock, and the
 * initializer runs again only if it has not produced a value yet. Successful values are cached;
 * failures and cancellation are never cached:
 *
 * - If the initializer throws, the exception is rethrown to the caller that ran it and the next
 *   waiter (or later call) retries.
 * - If the caller that is running the initializer is cancelled, [kotlinx.coroutines.CancellationException]
 *   propagates to that caller only. The lock is released and another waiter takes over resolution,
 *   so no other waiter observes a failure it did not cause.
 *
 * The initializer runs in the context of the caller that holds the lock.
 */
public class SuspendLazy<out T>(initializer: suspend () -> T) {

    private val mutex = Mutex()
    private var initializer: (suspend () -> T)? = initializer

    @Volatile
    private var value: Any? = Unresolved

    /** `true` once the initializer has produced a value. */
    public val isInitialized: Boolean
        get() = value !== Unresolved

    /** Returns the resolved value, running the initializer if no caller has resolved it yet. */
    @Suppress("UNCHECKED_CAST")
    public suspend fun get(): T {
        val fast = value
        if (fast !== Unresolved) return fast as T
        return mutex.withLock {
            val current = value
            if (current !== Unresolved) current as T else initialize()
        }
    }

    private suspend fun initialize(): T {
        val resolved = checkNotNull(initializer)()
        value = resolved
        // The initializer is no longer needed; release what it captured.
        initializer = null
        return resolved
    }

    private object Unresolved
}
