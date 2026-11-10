package com.santimattius.android.sample.demo

import dagger.Lazy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyAwaitDemoTest {

    @Test
    fun resolvesTheLazyOnTheGivenDispatcherAndReusesTheInstance() = runTest {
        NamedThread("default-thread").use { default ->
            val instance by kotlin.lazy { ExpensiveService() }
            val demo = LazyAwaitDemo(Lazy { instance }, default.dispatcher)

            assertEquals(
                listOf(
                    "constructed on: default-thread",
                    "second await() returned the same instance: true",
                ),
                demo.run().lines(),
            )
        }
    }
}
