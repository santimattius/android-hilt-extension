package io.github.santimattius.android.sample.feature

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

class LibraryGreeting {
    val message: String = "Hello from :sample-feature"
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryGreetingModule {
    @Provides
    fun provideLibraryGreeting(): LibraryGreeting = LibraryGreeting()
}
