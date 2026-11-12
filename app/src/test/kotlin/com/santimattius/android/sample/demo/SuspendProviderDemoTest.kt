package com.santimattius.android.sample.demo

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SuspendProviderDemoTest {

    @Test
    fun scopedProviderReturnsTheSharedInstanceAndUnscopedCreatesNewOnes() = runTest {
        AlternatingThreadDispatcher().use { io ->
            val shared = SingletonCounter()
            val observedGetThread = AtomicReference<String>()
            val demo = SuspendProviderDemo(
                scoped = Provider {
                    observedGetThread.set(Thread.currentThread().name)
                    shared
                },
                unscoped = Provider {
                    observedGetThread.set(Thread.currentThread().name)
                    FreshWidget()
                },
                io = io,
            )

            val result = demo.run().lines()

            assertEquals(
                listOf(
                    "scoped: same instance on every get(): true",
                    "unscoped: new instance on every get(): true",
                    "get() ran on: ${observedGetThread.get()}",
                ),
                result,
            )
        }
    }

    /** Sends each dispatch to a different named worker so a later probe cannot impersonate get(). */
    private class AlternatingThreadDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val workers = listOf("provider-a", "provider-b").map { name ->
            Executors.newSingleThreadExecutor { Thread(it, name) }
        }
        private val nextWorker = AtomicInteger()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            workers[nextWorker.getAndIncrement() % workers.size].execute(block)
        }

        override fun close() {
            workers.forEach(ExecutorService::shutdownNow)
        }
    }
}
