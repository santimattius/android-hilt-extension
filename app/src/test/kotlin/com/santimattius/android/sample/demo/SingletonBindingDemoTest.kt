package com.santimattius.android.sample.demo

import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SingletonBindingDemoTest {

    @Test
    fun reportsTheSameInstanceWhenTheProviderIsScoped() = runTest {
        val shared = SingletonCounter()
        val result = SingletonBindingDemo(Provider { shared }).run()

        assertTrue(result, result.contains("same instance: true"))
        assertTrue(result, result.contains("id=${shared.id}"))
    }

    @Test
    fun reportsDifferentInstancesWhenTheProviderIsNotScoped() = runTest {
        val result = SingletonBindingDemo(Provider { SingletonCounter() }).run()

        assertTrue(result, result.contains("same instance: false"))
    }

    @Test
    fun everyCounterGetsItsOwnIdentity() {
        assertTrue(SingletonCounter().id != SingletonCounter().id)
    }
}
