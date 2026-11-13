package io.github.santimattius.android.sample.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SuspendLazyDemoTest {

    @Test
    fun concurrentCallersRunTheInitializerOnce() = runTest {
        assertEquals(
            "5 concurrent callers got the same value; the initializer ran 1 time",
            SuspendLazyDemo().run(),
        )
    }

    @Test
    fun everyRunUsesAFreshLazy() = runTest {
        val demo = SuspendLazyDemo()
        demo.run()

        assertEquals(
            "5 concurrent callers got the same value; the initializer ran 1 time",
            demo.run(),
        )
    }
}
