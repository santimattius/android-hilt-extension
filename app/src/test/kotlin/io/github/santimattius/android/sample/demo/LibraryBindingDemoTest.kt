package io.github.santimattius.android.sample.demo

import io.github.santimattius.android.sample.feature.LibraryGreeting
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBindingDemoTest {
    @Test
    fun reportsLibraryGreetingAndTwoDistinctInstances() = runTest {
        var calls = 0
        val result = LibraryBindingDemo(Provider { calls++; LibraryGreeting() }).run()

        assertEquals("Hello from :sample-feature\ndistinct instances: true", result)
        assertEquals(2, calls)
    }

    @Test
    fun reportsSharedInstancesAccurately() = runTest {
        val shared = LibraryGreeting()
        var calls = 0
        val result = LibraryBindingDemo(Provider { calls++; shared }).run()

        assertEquals("Hello from :sample-feature\ndistinct instances: false", result)
        assertEquals(2, calls)
    }
}
