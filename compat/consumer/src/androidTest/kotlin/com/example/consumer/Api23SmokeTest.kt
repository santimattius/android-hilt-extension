package com.example.consumer

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.santimattius.android.hilt.coroutines.suspend.SuspendLazy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minimal smoke for the API 23 probe (docs/LIMITATIONS.md). It runs on whatever device it is given;
 * pass `-Pandroid.testInstrumentationRunnerArguments.expectedSdk=23` to fail unless it is API 23.
 */
class Api23SmokeTest {

    @Test
    fun runsOnTheExpectedApiLevel() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedSdk")?.toInt()
        if (expected != null) assertEquals(expected, Build.VERSION.SDK_INT)
    }

    @Test
    fun hiltApplicationAndInstrumentedActivityStart() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue(app is App)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals("hi", it.greeter.hi()) }
        }
    }

    @Test
    fun coroutinesRuntimeResolvesALazyValue() {
        assertEquals(42, runBlocking { SuspendLazy { 41 + 1 }.get() })
    }
}
