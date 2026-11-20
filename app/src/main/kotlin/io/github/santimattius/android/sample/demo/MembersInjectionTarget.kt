package io.github.santimattius.android.sample.demo

import dagger.MembersInjector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.santimattius.android.sample.greeting.GreetingRepository
import javax.inject.Inject

/**
 * A class with `@Inject` members and no injectable constructor: Hilt generates a `MembersInjector` for it.
 * It has no card and no Run button; the instrumented test of `MembersInjector.injectMembers` injects it
 * through [MembersInjectionEntryPoint] (opt-in `hiltTrace { traceMembersInjectors }`, enabled by this sample).
 */
class MembersInjectionTarget {
    @Inject
    lateinit var greetings: GreetingRepository
}

/** Reaches the `MembersInjector` from the singleton component, as non-Hilt code that injects by hand does. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MembersInjectionEntryPoint {
    fun membersInjector(): MembersInjector<MembersInjectionTarget>
}
