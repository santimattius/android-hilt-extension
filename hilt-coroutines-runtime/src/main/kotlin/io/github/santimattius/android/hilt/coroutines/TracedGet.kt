@file:OptIn(DelicateTracingApi::class)

package io.github.santimattius.android.hilt.coroutines

import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import dagger.Lazy
import io.github.santimattius.android.hilt.coroutines.suspend.BLOCKING_RESOLUTION_CATEGORY
import javax.inject.Provider

/**
 * Calls [Provider.get] inside a `hilt.resolve` section named [name], on the calling thread.
 *
 * The router that Dagger generates for a deferred binding only holds a binding id, so nothing in
 * generated code can name the requester of a plain `get()`. This helper is the opt-in: call it at your
 * own call site and pass a label for the requester (for example `"CheckoutScreen"`). The `hilt.create`
 * slice of the binding is written on the same thread track and nests under the section.
 *
 * Tracing is fail-open: the value and any exception of `get()` reach the caller unchanged, and a
 * failure of the tracer never breaks the call. When `hilt.resolve` is disabled, or no tracer is
 * installed, this is a plain `get()`.
 */
public fun <T> Provider<T>.getTraced(name: String): T {
    val section = beginResolveSection(name)
    try {
        return get()
    } finally {
        endResolveSection(section)
    }
}

/** The same as [Provider.getTraced] for a [Lazy]; the first call builds the value inside the section. */
public fun <T> Lazy<T>.getTraced(name: String): T {
    val section = beginResolveSection(name)
    try {
        return get()
    } finally {
        endResolveSection(section)
    }
}

/**
 * Opens the `hilt.resolve` section on the current thread and returns its closeable, or `null` when the
 * category is disabled or the tracer failed.
 */
private fun beginResolveSection(name: String): AutoCloseable? = try {
    // Never cache Tracer.global: the application installs the real tracer after startup.
    val tracer = Tracer.global
    if (!tracer.isCategoryEnabled(BLOCKING_RESOLUTION_CATEGORY)) {
        null
    } else {
        val holder = tracer.beginSectionWithMetadata(BLOCKING_RESOLUTION_CATEGORY, name, null, false)
        // The holder is shared per track: read the closeable before any other tracing call.
        val closeable = holder.closeable
        try {
            holder.metadata.dispatchToTraceSink()
            closeable
        } catch (_: Throwable) {
            endResolveSection(closeable)
            null
        }
    }
} catch (_: Throwable) {
    // Fail open: a tracing failure must never reach app code.
    null
}

/** Closes the section opened by [beginResolveSection]; a `null` token is a no-op and a failure is swallowed. */
private fun endResolveSection(section: AutoCloseable?) {
    try {
        section?.close()
    } catch (_: Throwable) {
        // Fail open.
    }
}
