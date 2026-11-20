package io.github.santimattius.android.sample.demo

import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

/**
 * Exposes a deferred `Provider` and `Lazy` of two sample bindings to the instrumented test of
 * `Provider.getTraced` / `Lazy.getTraced` (issue #84). It has no card and no Run button: the test calls
 * the helpers at its own call site, as an app would from a screen.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TracedGetEntryPoint {
    fun freshWidget(): Provider<FreshWidget>
    fun expensiveService(): Lazy<ExpensiveService>
}
