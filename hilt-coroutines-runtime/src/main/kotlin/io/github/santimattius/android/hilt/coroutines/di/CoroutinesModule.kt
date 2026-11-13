package io.github.santimattius.android.hilt.coroutines.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dispatchers and the application scope, bound to this library's own qualifiers.
 *
 * Tests replace the whole module with `@TestInstallIn(replaces = [CoroutinesModule::class])`
 * and bind the same qualifiers. Do not declare your own qualifiers for these types: a second
 * binding of the same type would be a duplicate.
 */
@Module
@InstallIn(SingletonComponent::class)
public object CoroutinesModule {

    @Provides
    @IoDispatcher
    public fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    public fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    public fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * A scope with a [SupervisorJob], so one failed child does not cancel its siblings. Cancel it
     * through [ApplicationScopeCloser] when the component goes away.
     */
    @Provides
    @Singleton
    @ApplicationScope
    public fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
