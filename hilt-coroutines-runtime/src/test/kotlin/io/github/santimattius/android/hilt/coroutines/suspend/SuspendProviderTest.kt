@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.santimattius.android.hilt.coroutines.suspend

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SuspendProviderTest {

    @Test
    fun getResolvesTheProviderOnTheGivenDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "provider-thread") }
        try {
            val provider = Provider { Thread.currentThread().name }

            val threadName = provider.suspending(executor.asCoroutineDispatcher()).get()

            assertEquals("provider-thread", threadName)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun everyGetAsksTheProviderAgain() = runTest {
        val calls = AtomicInteger()
        val suspending = Provider { "instance-${calls.incrementAndGet()}" }
            .suspending(StandardTestDispatcher(testScheduler))

        val first = async { suspending.get() }
        val second = async { suspending.get() }
        advanceUntilIdle()

        assertEquals(setOf("instance-1", "instance-2"), setOf(first.await(), second.await()))
        assertEquals(2, calls.get())
    }

    @Test
    fun providerFailureIsRethrownToTheCaller() = runTest {
        val boom = IllegalStateException("boom")
        val suspending = Provider<String> { throw boom }
            .suspending(StandardTestDispatcher(testScheduler))

        val result = async { runCatching { suspending.get() } }
        advanceUntilIdle()

        // Stack-trace recovery may copy the exception when it crosses withContext, so compare type and message.
        val thrown = result.await().exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals("boom", thrown?.message)
    }

    @Test
    fun cancellingBeforeTheDispatcherRunsNeverInvokesTheProvider() = runTest {
        val calls = AtomicInteger()
        val suspending = Provider { calls.incrementAndGet() }
            .suspending(StandardTestDispatcher(testScheduler))

        val caller = async(start = CoroutineStart.UNDISPATCHED) { suspending.get() }
        caller.cancel()
        advanceUntilIdle()

        assertTrue(caller.isCancelled)
        try {
            caller.await()
            fail("The cancelled caller must throw CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
        assertEquals(0, calls.get())
    }
}
