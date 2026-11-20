# Coroutine helpers

Dispatcher and scope qualifiers, suspending adapters for `Provider` and `Lazy`, a suspending lazy, and a trace section that follows a coroutine. Prerequisite: [Setup](SETUP.md#coroutine-helpers). Related: [Tracing](TRACING.md), [Limitations](LIMITATIONS.md).

Artifact: `hilt-coroutines-runtime`. Packages: `io.github.santimattius.android.hilt.coroutines` (`traceCoroutine`), `.di` (qualifiers, module, closer) and `.suspend` (adapters). The module runs `explicitApi()`; everything below is public API.

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


Both adapters also open a trace section around the whole call, so the `hilt.create` slice of a deferred binding resolved through them nests under it (see [Trace section](#trace-section-of-the-adapters)). The section does not change the contract below.

- A caller that is already cancelled fails fast with `CancellationException`.
- If cancellation arrives before the dispatcher runs the call, `get()` is never invoked.
- A `get()` that is already running cannot be interrupted, because it is a blocking call. The caller still gets `CancellationException` afterwards, not the result.
- An exception thrown by `get()` reaches the caller (the exception may be a stack-trace-recovered copy of the same type and message).

### Trace section of the adapters

`Provider.suspending(...)` opens the section `Provider.suspending` and `Lazy.await(...)` opens `Lazy.await`, in the category `hilt.resolve`, around the blocking `get()`. The names are fixed: the generic type is erased, so the section does not say which binding was requested. It is the requester marker, not the binding; the binding is the `hilt.create` slice inside it.

Evidence: on the sample's real Hilt graph (Android 15 emulator), `DeferredResolutionTraceTest` records, for a `Provider<FreshWidget>` and a `dagger.Lazy<ExpensiveService>`, a `hilt.create` slice strictly between the BEGIN and END of the helper section on the same track, with the calling coroutine on the instrumentation thread and on a `Dispatchers.Default` worker. The recorded order on the worker track is `BEGIN hilt.resolve` then `BEGIN hilt.create <binding>`, `END`, `END`. A suspended caller is written as several segments of the section (the same behaviour as `traceCoroutine`), so the caller's track shows empty `hilt.resolve` segments next to the one that holds the creation.

- Only a creation slice exists for the first resolution of a scoped binding; later calls leave an empty `hilt.resolve` section (cache hits have no slice of their own, see [Limitations](LIMITATIONS.md)).
- When `hilt.resolve` is disabled the adapters call `get()` through `withContext` as before; the category is checked first, so the disabled path adds no section wrapper. Not measured with `:benchmark-micro`.
- `SuspendLazy` does not open a section.
- A plain `Provider.get()` or `Lazy.get()` in your own code is not linked to its caller unless you call `getTraced` (next section).

### Linking a blocking `get()` to its caller: `getTraced`

```kotlin
import io.github.santimattius.android.hilt.coroutines.getTraced

class CheckoutScreen @Inject constructor(private val cart: Provider<Cart>) {
    fun open() {
        val value = cart.getTraced("CheckoutScreen")   // also available on dagger.Lazy
    }
}
```

`Provider<T>.getTraced(name)` and `Lazy<T>.getTraced(name)` are plain synchronous functions. They open a section in the category `hilt.resolve` named `name` (your label for the requester), call `get()` on the same thread, and close the section in a `finally`. The `hilt.create` slice of the binding is written on the same thread track, so it nests inside the section.

Nothing in the generated Dagger code can name the requester of a plain `get()` (the router only holds a binding id), so this is an opt-in at your own call site, not an automatic link.

Evidence: `TracedGetTraceTest` on the sample's real Hilt graph, on the debug build and on the R8-minified build (Android 15 emulator), for a `Provider<FreshWidget>` and a `dagger.Lazy<ExpensiveService>` reached through an `@EntryPoint`. The recorded events on one track are `BEGIN hilt.resolve CheckoutScreen`, `BEGIN hilt.create <binding>`, `END`, `END`. Unit tests (`TracedGetTest`) cover the returned value, exception propagation with the section closed, a disabled category, no installed tracer, and a tracer that throws.

- The helpers are fail-open: a tracing failure never breaks the `get()`, and the value or exception of `get()` reaches the caller unchanged.
- When `hilt.resolve` is disabled, or no tracer is installed, the call is a plain `get()` plus a category check; the disabled path allocates no section. Not measured with `:benchmark-micro`.
- Use it from a thread, not across suspension: the section is opened and closed on the calling thread. From a coroutine, use `traceCoroutine` or `Provider.suspending`.
- Not covered: a plain `get()` without the helper, `SuspendLazy`, and cache hits of a scoped binding (the section is recorded, with no `hilt.create` inside).

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

The category is yours; the Hilt categories `hilt.create` and `hilt.inject` are written by the plugin, and `hilt.resolve` by `Provider.suspending`, `Lazy.await` and `getTraced`. Whether your category is recorded depends on the driver (see [Tracing](TRACING.md)).
