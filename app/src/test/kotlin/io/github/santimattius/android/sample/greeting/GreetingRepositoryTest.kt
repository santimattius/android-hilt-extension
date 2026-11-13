package io.github.santimattius.android.sample.greeting

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingRepositoryTest {

    @Test
    fun audienceNameIsFormattedByTheInjectedFormatter() {
        val repository = GreetingRepository(GreetingFormatter(), audience = "perfetto")

        assertEquals("Perfetto", repository.audienceName())
    }

    @Test
    fun anotherAudienceGivesAnotherName() {
        val repository = GreetingRepository(GreetingFormatter(), audience = " hilt ")

        assertEquals("Hilt", repository.audienceName())
    }
}
