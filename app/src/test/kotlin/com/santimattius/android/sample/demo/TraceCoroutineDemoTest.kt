package com.santimattius.android.sample.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceCoroutineDemoTest {

    @Test
    fun returnsTheBlockResultAndReportsTheCategoryState() = runTest {
        NamedThread("io-thread").use { io ->
            val result = TraceCoroutineDemo(io.dispatcher).run()

            assertEquals(
                listOf(
                    "traceCoroutine returned: refreshed on io-thread",
                    "section: sample.coroutine / refresh (recorded: false)",
                ),
                result.lines(),
            )
        }
    }
}
