package com.santimattius.android.sample.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockingResolutionDemoTest {

    @Test
    fun cancellationDoesNotInterruptARunningBlockingGet() = runTest {
        val result = BlockingResolutionDemo(Dispatchers.IO).runWith(workMillis = 50)

        assertEquals(
            listOf(
                "caller cancelled while get() was running: true",
                "the running get() still finished: true",
            ),
            result.lines(),
        )
    }
}
