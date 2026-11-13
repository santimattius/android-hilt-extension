package io.github.santimattius.android.sample.greeting

import javax.inject.Inject

/** Unscoped constructor-injected binding: every request creates a new instance. */
class GreetingFormatter @Inject constructor() {

    fun displayName(raw: String): String =
        raw.trim().ifEmpty { FALLBACK_NAME }.replaceFirstChar { it.titlecase() }

    private companion object {
        const val FALLBACK_NAME = "World"
    }
}
