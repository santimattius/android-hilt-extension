# Coroutine helpers

Dispatcher and scope qualifiers, suspending adapters for `Provider` and `Lazy`, a suspending lazy, and a trace section that follows a coroutine. Prerequisite: [Setup](SETUP.md#coroutine-helpers). Related: [Tracing](TRACING.md), [Limitations](LIMITATIONS.md).

Artifact: `hilt-coroutines-runtime`. Packages: `com.santimattius.android.hilt.coroutines` (`traceCoroutine`), `.di` (qualifiers, module, closer) and `.suspend` (adapters). The module runs `explicitApi()`; everything below is public API.

## Dispatchers and scope

`CoroutinesModule` is installed in `SingletonComponent` and binds:

| Binding | Qualifier | Value |
|---------|-----------|-------|
| `CoroutineDispatcher` | `@IoDispatcher` | `Dispatchers.IO` |
| `CoroutineDispatcher` | `@DefaultDispatcher` | `Dispatchers.Default` |
| `CoroutineDispatcher` | `@MainDispatcher` | `Dispatchers.Main` |
| `CoroutineScope` | `@ApplicationScope`, `@Singleton` | `SupervisorJob()` plus the default dispatcher |

```kotlin
class Repo @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
)
```

Do not declare your own qualifiers for these types: a second binding of the same type is a duplicate. Tests replace the module with `@TestInstallIn(replaces = [CoroutinesModule::class])` and the same qualifiers.

> [!WARNING]
> The application scope is never cancelled automatically.

`SingletonComponent` has no destroy callback. `ApplicationScopeCloser` (`@Singleton`, `AutoCloseable`) cancels it when its owner calls `close()`, for example a test rule. This is covered by `CoroutinesModuleUnitTest`.

## SuspendProvider

```kotlin
class Repo @Inject constructor(
    parser: Provider<Parser>,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val parsers: SuspendProvider<Parser> = parser.suspending(io)
    suspend fun parse() = parsers.get()
}
```

`Provider.suspending(dispatcher)` runs `Provider.get()` on `dispatcher`. Each call asks the binding again: a scoped binding returns its shared instance, an unscoped one builds a new instance.

## Lazy.await

```kotlin
suspend fun warmUp(cache: dagger.Lazy<Cache>) = cache.await(dispatcher = Dispatchers.Default)
```

Resolves a `dagger.Lazy` on the dispatcher (default `Dispatchers.Default`). Caching stays the job of the Dagger `Lazy`; `await` adds no cache.

## Blocking resolution contract

Both adapters share one internal helper (`resolveBlockingOn`, a `withContext`). Evidence: `SuspendProviderTest` and `LazyAwaitTest` cover the cancel-before-dispatch and failure cases; the sample's `BlockingResolutionDemoTest` demonstrates the uninterruptible running call.


- A caller that is already cancelled fails fast with `CancellationException`.
- If cancellation arrives before the dispatcher runs the call, `get()` is never invoked.
- A `get()` that is already running cannot be interrupted, because it is a blocking call. The caller still gets `CancellationException` afterwards, not the result.
- An exception thrown by `get()` reaches the caller (the exception may be a stack-trace-recovered copy of the same type and message).

## SuspendLazy

```kotlin
val settings = SuspendLazy { loadSettings() }   // initializer may suspend
val value = settings.get()
```

- Mutex-guarded: concurrent callers wait for the one holding the lock, so the initializer runs once.
- A successful value is cached; failures are not. If the initializer throws, the next waiter or later call retries.
- If the caller running the initializer is cancelled, only that caller sees `CancellationException`; another waiter takes over.
- The initializer runs in the context of the caller that holds the lock. `isInitialized` tells whether a value exists.

## traceCoroutine

```kotlin
suspend fun refresh() = traceCoroutine(category = "app.sync", name = "refresh") { fetch() }
```

A passthrough to `androidx.tracing` that reads `Tracer.global` on every call. The section follows the coroutine across suspension points and thread switches, so a suspended coroutine is written as several thread segments of one named section. It is a plain call of the block when the category is disabled or no driver is installed. The result and any exception reach the caller unchanged.

The category is yours; the Hilt categories `hilt.create` and `hilt.inject` are written by the plugin. Whether your category is recorded depends on the driver (see [Tracing](TRACING.md)).
