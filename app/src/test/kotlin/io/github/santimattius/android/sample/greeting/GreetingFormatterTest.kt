package io.github.santimattius.android.sample.greeting

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingFormatterTest {

    private val formatter = GreetingFormatter()

    @Test
    fun trimsAndCapitalizesTheFirstLetter() {
        assertEquals("Perfetto", formatter.displayName("  perfetto "))
    }

    @Test
    fun keepsAnAlreadyCapitalizedNameUntouched() {
        assertEquals("Android", formatter.displayName("Android"))
    }

    @Test
    fun fallsBackToWorldForABlankName() {
        assertEquals("World", formatter.displayName("   "))
    }
}
