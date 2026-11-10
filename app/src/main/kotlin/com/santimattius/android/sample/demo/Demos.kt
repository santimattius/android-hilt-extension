package com.santimattius.android.sample.demo

import com.santimattius.android.hilt.coroutines.di.ApplicationScope
import com.santimattius.android.hilt.coroutines.di.DefaultDispatcher
import com.santimattius.android.hilt.coroutines.di.IoDispatcher
import com.santimattius.android.hilt.coroutines.di.MainDispatcher
import com.santimattius.android.hilt.coroutines.suspend.SuspendLazy
import com.santimattius.android.hilt.coroutines.suspend.await
import com.santimattius.android.hilt.coroutines.suspend.suspending
import com.santimattius.android.hilt.coroutines.traceCoroutine
import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A demo that runs in a coroutine and returns the text shown on its card. */
fun interface SuspendDemo {
    suspend fun run(): String
}

private val identity = AtomicInteger()

/** Scoped binding: Hilt builds it once per process, so only the first request has a create section. */
@Singleton
class SingletonCounter @Inject constructor() {
    val id: Int = identity.incrementAndGet()
}

/** Unscoped binding, requested through a `Provider`, so every `get()` builds a new instance. */
class FreshWidget @Inject constructor() {
    val id: Int = identity.incrementAndGet()
}

/** Binding with a deliberately slow constructor, resolved through a `dagger.Lazy`. */
class ExpensiveService @Inject constructor() {
    val createdOn: String = Thread.currentThread().name

    init {
        Thread.sleep(CONSTRUCTION_MILLIS)
    }

    private companion object {
        const val CONSTRUCTION_MILLIS = 100L
    }
}

class SingletonBindingDemo @Inject constructor(
    private val counter: Provider<SingletonCounter>,
) : SuspendDemo {
    override suspend fun run(): String {
        val first = counter.get()
        val second = counter.get()
        return "same instance: ${first === second} (id=${first.id})"
    }
}

class DispatchersDemo @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
    @MainDispatcher private val main: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : SuspendDemo {
    override suspend fun run(): String {
        val ioThread = withContext(io) { threadName() }
        val defaultThread = withContext(default) { threadName() }
        val mainThread = withContext(main) { threadName() }
        val (scopeThread, active) = scope.async { threadName() to isActive }.await()
        return listOf(
            "@IoDispatcher: $ioThread",
            "@DefaultDispatcher: $defaultThread",
            "@MainDispatcher: $mainThread",
            "@ApplicationScope: $scopeThread (active: $active)",
        ).joinToString("\n")
    }
}

class SuspendProviderDemo @Inject constructor(
    private val scoped: Provider<SingletonCounter>,
    private val unscoped: Provider<FreshWidget>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SuspendDemo {
    override suspend fun run(): String {
        val scopedProvider = scoped.suspending(io)
        val unscopedProvider = unscoped.suspending(io)
        val sameScoped = scopedProvider.get() === scopedProvider.get()
        val differentUnscoped = unscopedProvider.get() !== unscopedProvider.get()
        val thread = withContext(io) { threadName() }
        return listOf(
            "scoped: same instance on every get(): $sameScoped",
            "unscoped: new instance on every get(): $differentUnscoped",
            "get() ran on: $thread",
        ).joinToString("\n")
    }
}

class SuspendLazyDemo @Inject constructor() : SuspendDemo {
    override suspend fun run(): String = coroutineScope {
        val calls = AtomicInteger()
        val lazy = SuspendLazy {
            delay(INITIALIZER_MILLIS)
            calls.incrementAndGet()
        }
        val values = (1..CALLERS).map { async { lazy.get() } }.awaitAll()
        val same = values.toSet().size == 1
        val outcome = if (same) "got the same value" else "got different values"
        "$CALLERS concurrent callers $outcome; the initializer ran ${calls.get()} time"
    }

    private companion object {
        const val CALLERS = 5
        const val INITIALIZER_MILLIS = 100L
    }
}

class LazyAwaitDemo @Inject constructor(
    private val service: dagger.Lazy<ExpensiveService>,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : SuspendDemo {
    override suspend fun run(): String {
        val first = service.await(default)
        val second = service.await(default)
        return listOf(
            "constructed on: ${first.createdOn}",
            "second await() returned the same instance: ${first === second}",
        ).joinToString("\n")
    }
}

@OptIn(DelicateTracingApi::class)
class TraceCoroutineDemo @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : SuspendDemo {
    override suspend fun run(): String {
        val category = DemoCatalog.TRACE_COROUTINE_CATEGORY
        val recorded = Tracer.global.isCategoryEnabled(category)
        val value = traceCoroutine(category, DemoCatalog.TRACE_COROUTINE_NAME) {
            delay(WORK_MILLIS)
            withContext(io) {
                delay(WORK_MILLIS)
                "refreshed on ${threadName()}"
            }
        }
        return listOf(
            "traceCoroutine returned: $value",
            "section: $category / ${DemoCatalog.TRACE_COROUTINE_NAME} (recorded: $recorded)",
        ).joinToString("\n")
    }

    private companion object {
        const val WORK_MILLIS = 50L
    }
}

class BlockingResolutionDemo @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : SuspendDemo {
    override suspend fun run(): String = runWith(WORK_MILLIS)

    internal suspend fun runWith(workMillis: Long): String = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val finished = AtomicBoolean(false)
        val blocking = Provider {
            started.complete(Unit)
            Thread.sleep(workMillis)
            finished.set(true)
            "resolved"
        }.suspending(io)
        val caller = launch { blocking.get() }
        started.await()
        caller.cancel()
        caller.join()
        listOf(
            "caller cancelled while get() was running: ${caller.isCancelled}",
            "the running get() still finished: ${finished.get()}",
        ).joinToString("\n")
    }

    private companion object {
        const val WORK_MILLIS = 300L
    }
}

/** The thread name without the " @coroutine#N" suffix that coroutine debug mode appends. */
private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
