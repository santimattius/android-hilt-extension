package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** The `hiltTrace { }` block of the consuming application module. */
public abstract class HiltTraceExtension {

    /** Kill switch: when false no variant is instrumented. */
    public abstract val enabled: Property<Boolean>

    /** Names of the variants to instrument. Release is never part of the default. */
    public abstract val variants: ListProperty<String>

    /**
     * Opt-in, off by default: wraps `injectMembers(T)` of the generated `*_MembersInjector` classes in a
     * `hilt.inject` slice named `T.injectMembers`. Only a consumer that receives a `MembersInjector<T>`
     * through DI calls it (the component calls the static setters), so for most apps it records nothing.
     */
    public abstract val traceMembersInjectors: Property<Boolean>

    internal fun applyDefaults() {
        enabled.convention(true)
        variants.convention(DEFAULT_VARIANTS)
        traceMembersInjectors.convention(false)
    }

    private companion object {
        val DEFAULT_VARIANTS = listOf("debug", "benchmark")
    }
}
