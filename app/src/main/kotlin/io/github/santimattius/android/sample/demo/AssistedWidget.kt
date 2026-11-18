package io.github.santimattius.android.sample.demo

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.santimattius.android.sample.greeting.GreetingRepository

/**
 * A class built through its `@AssistedFactory`: [label] is passed by the caller, the repository comes
 * from the graph. It has no card and no Run button; the instrumented test of assisted creation builds it.
 */
class AssistedWidget @AssistedInject constructor(
    val greetings: GreetingRepository,
    @Assisted val label: String,
) {
    @AssistedFactory
    fun interface Factory {
        fun create(label: String): AssistedWidget
    }
}

/** Reaches the factory from the singleton component, as non-Hilt code does. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistedWidgetEntryPoint {
    fun assistedWidgetFactory(): AssistedWidget.Factory
}
