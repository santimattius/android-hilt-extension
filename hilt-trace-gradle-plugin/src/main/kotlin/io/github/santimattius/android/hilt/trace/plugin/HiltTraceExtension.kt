package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** The `hiltTrace { }` block of the consuming application module. */
public abstract class HiltTraceExtension {

    /** Kill switch: when false no variant is instrumented. */
    public abstract val enabled: Property<Boolean>

    /** Names of the variants to instrument. Release is never part of the default. */
    public abstract val variants: ListProperty<String>

    internal fun applyDefaults() {
        enabled.convention(true)
        variants.convention(DEFAULT_VARIANTS)
    }

    private companion object {
        val DEFAULT_VARIANTS = listOf("debug", "benchmark")
    }
}
