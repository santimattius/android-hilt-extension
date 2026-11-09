package com.santimattius.android.sample.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Qualifier of the audience shown in the greeting; it appears in the trace slice of its provider. */
const val AUDIENCE = "audience"

@Module
@InstallIn(SingletonComponent::class)
object SampleModule {

    @Provides
    @Named(AUDIENCE)
    fun provideAudience(): String = "perfetto"
}
