package com.santimattius.android.sample.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
}
