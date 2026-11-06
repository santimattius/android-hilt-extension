@file:OptIn(ExperimentalCoroutinesApi::class)

package com.santimattius.android.hilt.coroutines.suspend

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SuspendLazyTest {

    @Test
    fun tenConcurrentAwaitersInvokeTheDelegateOnce() = runTest {
        val calls = AtomicInteger()
        val lazy = SuspendLazy {
            calls.incrementAndGet()
            delay(100)
            "value"
        }

        val results = (1..10).map { async { lazy.get() } }.awaitAll()

        assertEquals(1, calls.get())
        assertEquals(List(10) { "value" }, results)
    }

    @Test
    fun laterCallsReuseTheResolvedValueWithoutInvokingTheDelegate() = runTest {
        val calls = AtomicInteger()
        val lazy = SuspendLazy { "v${calls.incrementAndGet()}" }

        assertFalse(lazy.isInitialized)
        val first = lazy.get()
        val second = lazy.get()

        assertEquals("v1", first)
        assertEquals("v1", second)
        assertEquals(1, calls.get())
        assertTrue(lazy.isInitialized)
    }

    @Test
    fun cancellingTheResolvingCallerPropagatesAndDoesNotFailOtherWaiters() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val lazy = SuspendLazy {
            val attempt = calls.incrementAndGet()
            if (attempt == 1) gate.await()
            "attempt-$attempt"
        }

        val resolver = async(start = CoroutineStart.UNDISPATCHED) { lazy.get() }
        val waiter = async { lazy.get() }
        runCurrent()
        resolver.cancel()
        advanceUntilIdle()

        assertTrue(resolver.isCancelled)
        try {
            resolver.await()
            fail("The cancelled resolver must throw CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
        // The waiter did not receive a failure: it took over resolution and succeeded.
        assertEquals("attempt-2", waiter.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun cancellingAWaiterLeavesTheResolverAndOtherWaitersUnaffected() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val lazy = SuspendLazy {
            calls.incrementAndGet()
            gate.await()
            "value"
        }

        val resolver = async { lazy.get() }
        val cancelledWaiter = async { lazy.get() }
        val survivingWaiter = async { lazy.get() }
        runCurrent()
        cancelledWaiter.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(cancelledWaiter.isCancelled)
        assertEquals("value", resolver.await())
        assertEquals("value", survivingWaiter.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationIsNotCachedAfterwards() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val lazy = SuspendLazy {
            if (calls.incrementAndGet() == 1) gate.await()
            "ok"
        }

        val job = launch { lazy.get() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertFalse(lazy.isInitialized)
        assertEquals("ok", lazy.get())
    }

    @Test
    fun exceptionIsRethrownToTheCaller() = runTest {
        val boom = IllegalStateException("boom")
        val lazy = SuspendLazy<String> { throw boom }

        try {
            lazy.get()
            fail("The delegate failure must be rethrown")
        } catch (thrown: IllegalStateException) {
            assertSame(boom, thrown)
        }
        assertFalse(lazy.isInitialized)
    }

    @Test
    fun exceptionIsNotCachedAndTheNextCallRetries() = runTest {
        val calls = AtomicInteger()
        val lazy = SuspendLazy {
            if (calls.incrementAndGet() == 1) error("first attempt fails")
            "recovered"
        }

        try {
            lazy.get()
            fail("The first call must fail")
        } catch (expected: IllegalStateException) {
            assertEquals("first attempt fails", expected.message)
        }

        assertEquals("recovered", lazy.get())
        assertEquals("recovered", lazy.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun concurrentWaitersEachRetryAfterAFailureInsteadOfSharingIt() = runTest {
        val calls = AtomicInteger()
        val lazy = SuspendLazy {
            delay(10)
            if (calls.incrementAndGet() == 1) error("transient")
            "ok"
        }

        val first = async { runCatching { lazy.get() } }
        val second = async { runCatching { lazy.get() } }

        assertTrue(first.await().exceptionOrNull() is IllegalStateException)
        assertEquals("ok", second.await().getOrThrow())
        assertEquals(2, calls.get())
    }

    @Test
    fun aNullResultIsAValueAndIsCached() = runTest {
        val calls = AtomicInteger()
        val lazy = SuspendLazy<String?> {
            calls.incrementAndGet()
            null
        }

        assertEquals(null, lazy.get())
        assertEquals(null, lazy.get())
        assertEquals(1, calls.get())
        assertTrue(lazy.isInitialized)
    }
}
