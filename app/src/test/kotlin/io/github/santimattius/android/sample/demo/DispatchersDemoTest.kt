package io.github.santimattius.android.sample.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class DispatchersDemoTest {

    @Test
    fun reportsTheThreadOfEveryQualifiedDispatcherAndTheScope() = runTest {
        NamedThread("io-thread").use { io ->
            NamedThread("default-thread").use { default ->
                NamedThread("main-thread").use { main ->
                    val scope = CoroutineScope(SupervisorJob() + default.dispatcher)
                    val demo = DispatchersDemo(io.dispatcher, default.dispatcher, main.dispatcher, scope)

                    val result = demo.run()

                    assertEquals(
                        listOf(
                            "@IoDispatcher: io-thread",
                            "@DefaultDispatcher: default-thread",
                            "@MainDispatcher: main-thread",
                            "@ApplicationScope: default-thread (active: true)",
                        ),
                        result.lines(),
                    )
                    scope.cancel()
                }
            }
        }
    }
}
