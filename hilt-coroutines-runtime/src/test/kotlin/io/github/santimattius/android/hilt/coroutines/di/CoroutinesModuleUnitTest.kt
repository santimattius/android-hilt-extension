@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.santimattius.android.hilt.coroutines.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks of the shipped module; the Hilt component behavior is covered by androidTest. */
class CoroutinesModuleUnitTest {

    @Test
    fun dispatcherBindingsAreTheStandardDispatchers() {
        assertSame(Dispatchers.IO, CoroutinesModule.ioDispatcher())
        assertSame(Dispatchers.Default, CoroutinesModule.defaultDispatcher())
        assertSame(Dispatchers.Main, CoroutinesModule.mainDispatcher())
    }

    @Test
    fun applicationScopeRunsOnTheGivenDefaultDispatcher() {
        val dispatcher = UnconfinedTestDispatcher()

        val scope = CoroutinesModule.applicationScope(dispatcher)

        assertSame(dispatcher, scope.coroutineContext[ContinuationInterceptor])
    }

    @Test
    fun aFailingChildDoesNotCancelTheScopeOrItsSiblings() = runTest {
        val scope = CoroutinesModule.applicationScope(UnconfinedTestDispatcher(testScheduler))
        val siblingResult = CompletableDeferred<String>()
        val failing = scope.launch(CoroutineExceptionHandler { _, _ -> }) {
            error("boom")
        }
        val sibling = scope.launch { siblingResult.complete("alive") }

        assertTrue(failing.isCancelled)
        assertEquals("alive", siblingResult.await())
        assertTrue(sibling.isCompleted && !sibling.isCancelled)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun withoutAnyCloseCallTheScopeAndItsRunningChildStayActive() = runTest {
        val scope = CoroutinesModule.applicationScope(UnconfinedTestDispatcher(testScheduler))
        // Created like Hilt does, but nobody calls close(): nothing cancels the scope on its own.
        ApplicationScopeCloser(scope)
        val running = scope.launch { awaitCancellation() }

        testScheduler.advanceUntilIdle()

        assertTrue(running.isActive)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun aChildOnARealDispatcherKeepsRunningWhenNobodyClosesTheScope() = runTest {
        val scope = CoroutinesModule.applicationScope(Dispatchers.Default)
        ApplicationScopeCloser(scope)
        val started = CompletableDeferred<Unit>()
        val running = scope.launch { started.complete(Unit); awaitCancellation() }

        try {
            // Real time on a real dispatcher, so a regression fails in seconds instead of waiting for the runTest limit.
            withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }

            assertTrue(running.isActive)
            assertTrue(scope.coroutineContext[Job]!!.isActive)
        } finally {
            scope.coroutineContext[Job]!!.cancel()
        }
    }

    @Test
    fun closerCancelsTheScopeAndItsRunningJobs() = runTest {
        val scope = CoroutinesModule.applicationScope(UnconfinedTestDispatcher(testScheduler))
        val running = scope.launch { awaitCancellation() }
        assertTrue(running.isActive)

        ApplicationScopeCloser(scope).close()

        assertTrue(running.isCancelled)
        assertFalse(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun closerCancelsAnyScopeItIsGiven() = runTest {
        val other = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val job = other.launch { awaitCancellation() }

        ApplicationScopeCloser(other).close()

        assertTrue(job.isCancelled)
    }
}
