package com.santimattius.android.hilt.coroutines.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** One distinct, recognizable dispatcher per qualifier. */
@OptIn(ExperimentalCoroutinesApi::class)
object TestDispatchers {
    val io: CoroutineDispatcher = UnconfinedTestDispatcher()
    val default: CoroutineDispatcher = UnconfinedTestDispatcher()
    val main: CoroutineDispatcher = UnconfinedTestDispatcher()
}

/**
 * Consumer-style replacement of the library module. It binds the library's own qualifiers, so a
 * duplicate binding would fail the Hilt build.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CoroutinesModule::class])
object TestCoroutinesModule {
    @Provides @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = TestDispatchers.io

    @Provides @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = TestDispatchers.default

    @Provides @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = TestDispatchers.main

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + TestDispatchers.default)
}
