package com.santimattius.android.sample.di

import android.content.Context
import com.santimattius.android.sample.android.ChoreographerFrameClock
import com.santimattius.android.sample.demo.FrameGapSampler
import com.santimattius.android.sample.demo.FrameSampler
import com.santimattius.android.sample.demo.MonotonicClock
import com.santimattius.android.sample.demo.SimulatedWork
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Qualifier of the audience shown in the greeting; it appears in the trace slice of its provider.
 * The provider is scoped on purpose: Dagger inlines an unscoped `@Provides`, which would leave the
 * qualified binding without a provider class to instrument and so without a slice.
 */
const val AUDIENCE = "audience"

@Module
@InstallIn(SingletonComponent::class)
object SampleModule {

    @Provides
    @Singleton
    @Named(AUDIENCE)
    fun provideAudience(): String = "perfetto"

    /** Real implementations of the demo's test seams; unit tests replace them with a fake clock. */
    @Provides
    fun provideSimulatedWork(): SimulatedWork = SimulatedWork { Thread.sleep(it) }

    @Provides
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { System.nanoTime() }

    @Provides
    fun provideFrameSampler(@ApplicationContext context: Context, clock: MonotonicClock): FrameSampler =
        FrameGapSampler(ChoreographerFrameClock(context), clock)
}
