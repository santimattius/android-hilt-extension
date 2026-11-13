package io.github.santimattius.android.sample.demo

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** A dispatcher backed by one thread with a known name, so a test can tell where work ran. */
class NamedThread(val name: String) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    override fun close() {
        executor.shutdownNow()
    }
}
