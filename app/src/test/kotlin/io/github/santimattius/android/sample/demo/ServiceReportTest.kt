package io.github.santimattius.android.sample.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceReportTest {

    @Test
    fun startsEmptyAndKeepsTheLatestMessage() {
        val report = ServiceReport()
        assertNull(report.message.value)

        report.publish("first")
        report.publish("second")

        assertEquals("second", report.message.value)
    }
}
