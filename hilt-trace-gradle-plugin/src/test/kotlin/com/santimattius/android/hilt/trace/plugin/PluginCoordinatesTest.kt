package com.santimattius.android.hilt.trace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCoordinatesTest {

    @Test
    fun `compiler and runtime use the library group and a resolved version`() {
        assertTrue(PluginCoordinates.compiler, Regex("com\\.santimattius\\.android:hilt-trace-compiler:\\d+\\.\\d+\\.\\d+\\S*").matches(PluginCoordinates.compiler))
        assertTrue(PluginCoordinates.runtime, Regex("com\\.santimattius\\.android:hilt-trace-runtime:\\d+\\.\\d+\\.\\d+\\S*").matches(PluginCoordinates.runtime))
    }

    @Test
    fun `both artifacts share one version`() {
        assertEquals(PluginCoordinates.compiler.substringAfterLast(':'), PluginCoordinates.runtime.substringAfterLast(':'))
    }
}
