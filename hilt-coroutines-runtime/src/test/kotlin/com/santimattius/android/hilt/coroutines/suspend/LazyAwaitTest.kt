@file:OptIn(ExperimentalCoroutinesApi::class)

package com.santimattius.android.hilt.coroutines.suspend

import dagger.Lazy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

class LazyAwaitTest {

    @Test
    fun awaitResolvesTheLazyOnTheGivenDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "lazy-thread") }
        try {
            val lazy = Lazy { Thread.currentThread().name }

            assertEquals("lazy-thread", lazy.await(executor.asCoroutineDispatcher()))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun awaitDelegatesToTheLazyEachTime() = runTest {
        val calls = AtomicInteger()
        val lazy = Lazy { calls.incrementAndGet() }
        val dispatcher = StandardTestDispatcher(testScheduler)

        val first = async { lazy.await(dispatcher) }
        advanceUntilIdle()
        val second = async { lazy.await(dispatcher) }
        advanceUntilIdle()

        // Caching is the job of the Dagger Lazy itself; await does not add or hide a cache.
        assertEquals(1, first.await())
        assertEquals(2, second.await())
    }

    @Test
    fun lazyFailureIsRethrownToTheCaller() = runTest {
        val boom = IllegalStateException("boom")
        val lazy = Lazy<String> { throw boom }

        val result = async { runCatching { lazy.await(StandardTestDispatcher(testScheduler)) } }
        advanceUntilIdle()

        // Stack-trace recovery may copy the exception when it crosses withContext, so compare type and message.
        val thrown = result.await().exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals("boom", thrown?.message)
    }

    @Test
    fun cancellingBeforeTheDispatcherRunsNeverResolvesTheLazy() = runTest {
        val calls = AtomicInteger()
        val lazy = Lazy { calls.incrementAndGet() }
        val dispatcher = StandardTestDispatcher(testScheduler)

        val caller = async(start = CoroutineStart.UNDISPATCHED) { lazy.await(dispatcher) }
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
