package io.github.santimattius.android.sample.demo

import io.github.santimattius.android.sample.feature.LibraryGreeting
import javax.inject.Inject
import javax.inject.Provider

class LibraryBindingDemo @Inject constructor(
    private val greetings: Provider<LibraryGreeting>,
) : SuspendDemo {
    override suspend fun run(): String {
        val first = greetings.get()
        val second = greetings.get()
        return "${first.message}\ndistinct instances: ${first !== second}"
    }
}
