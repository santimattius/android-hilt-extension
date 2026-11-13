package io.github.santimattius.android.hilt.coroutines.di

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Destroy hook of the `@ApplicationScope` scope.
 *
 * Dagger and Hilt give a `SingletonComponent` no callback when it is destroyed, so the owner of
 * the component calls [close] at that point, for example a test rule after each test. Closing
 * cancels the scope and every job running in it.
 */
@Singleton
public class ApplicationScopeCloser @Inject public constructor(
    @ApplicationScope private val scope: CoroutineScope,
) : AutoCloseable {

    override fun close() {
        scope.cancel("The component that owns the application scope was destroyed")
    }
}
