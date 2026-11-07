package com.santimattius.android.hilt.coroutines.di

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Hilt component test. [TestCoroutinesModule] replaces the library module for every test in this
 * APK (a `@TestInstallIn` replacement is global), so these tests prove injection with the
 * library's qualifiers, replaceability and the destroy hook against a real Hilt component.
 */
@HiltAndroidTest
class CoroutinesModuleTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject @field:IoDispatcher
    lateinit var io: CoroutineDispatcher

    @Inject @field:DefaultDispatcher
    lateinit var defaultDispatcher: CoroutineDispatcher

    @Inject @field:MainDispatcher
    lateinit var main: CoroutineDispatcher

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var scopeCloser: ApplicationScopeCloser

    @Before
    fun inject() = hilt.inject()

    @Test
    fun eachQualifierResolvesToItsOwnDispatcher() {
        assertSame(TestDispatchers.io, io)
        assertSame(TestDispatchers.default, defaultDispatcher)
        assertSame(TestDispatchers.main, main)
        assertNotSame(io, defaultDispatcher)
        assertNotSame(defaultDispatcher, main)
    }

    @Test
    fun testInstallInReplacesTheLibraryModule() {
        // The scope is built by the replacement module on the replacement default dispatcher.
        assertSame(TestDispatchers.default, scope.coroutineContext[ContinuationInterceptor])
    }

    @Test
    fun applicationScopeIsCancelledWhenTheComponentIsDestroyed() {
        val started = CompletableDeferred<Unit>()
        val job = scope.launch { started.complete(Unit); awaitCancellation() }
        assertTrue(started.isCompleted)
        assertTrue(job.isActive)

        scopeCloser.close()

        assertTrue(job.isCancelled)
        assertFalse(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun everyRunningJobOfTheScopeIsCancelled() {
        val first = scope.launch { awaitCancellation() }
        val second = scope.launch { awaitCancellation() }

        scopeCloser.close()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}
