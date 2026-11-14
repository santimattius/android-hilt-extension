# Limitations and known constraints

What Hilt Trace and the coroutine helpers do not cover, and which claims lack proof. Each item names its evidence. Items marked **unverified** are expected from the design but not backed by a test or a run in this repository. Related: [Setup](SETUP.md), [Tracing](TRACING.md), [Coroutines](COROUTINES.md).

## Not instrumented

Confirmed against Hilt 2.60.1 output.

| What | Why |
|------|-----|
| Unscoped bindings built inline inside a component accessor | Dagger inlines them; only the accessor section covers them. If something requests a `Provider<T>`/`Lazy<T>` of the binding, it gets a `SwitchingProvider` case and a `hilt.create` section. The sample's `provideAudience` is `@Singleton` for this reason. |
| `@Binds` and multibindings | No `SwitchingProvider` case. |
| The router `get()` of a `SwitchingProvider` | Not traced. |
| `DoubleCheck` cache hits | Only the first creation of a scoped binding has a section (`TraceCaptureTest.aSingletonIsCreatedOnceNoMatterHowOftenItIsRequested`). |
| Constructor arguments of a created object | One section per case; no sections for the arguments. |
| `*_MembersInjector.injectMembers` | Hilt 2.60.1 output has no caller; a `MembersInjector<T>` requested through DI is not traced. |
| Release variants | Unchanged by design (`ReleaseUnchangedTest`). |

## Build and environment

| Constraint | Evidence |
|------------|----------|
| Application module only; the plugin registers on `com.android.application`. | `HiltTracePlugin`. |
| Hilt `fastInit` must stay on (Hilt's default). With `-Pdagger.hilt.fastInit=false` Hilt generates no `SwitchingProvider`: creation sections are skipped and one warning is logged. | `HiltTracePlugin`, `PluginWiringTest`. |
| Coupled to the shape of Hilt-generated classes (verified on 2.60.1). An unknown shape, a method near 64 KB, or any error leaves the class unchanged with one warning. | `FailOpenTest`, `ASM` tests on captured output. |
| A library needs `ksp(hilt-trace-compiler)` and `hiltTrace.module`, or its entry points are not instrumented. The build passes either way. | `LibraryInstrumentationTest`, `StaleCatalogueTest`. |
| Library coverage is shown only with a hand-written provider fixture, not Hilt output from a real library module. | `LibraryInstrumentationTest` header. |
| Fragments are covered by ASM tests only; the sample has no Fragment. | `InjectRuleTest`. |
| KSP only; there is no kapt path. | Project constraint, `CatalogueProcessor`. |
| `minSdk` 24 for the library modules and the sample. Lower values are untested. | `gradle.properties`. |
| Validated versions are listed in [Setup: Supported toolchains](SETUP.md#supported-toolchains). Others are unverified. | `gradle/libs.versions.toml`, `.github/workflows/compat.yml`. |

## Runtime

| Constraint | Evidence |
|------------|----------|
| With a category disabled, `HiltTrace.begin` returns `null` and allocates nothing; the call site still runs in instrumented builds. The cost of that check has **not been measured**. | `HiltTraceTest` (disabled-category behavior); no benchmark exists. |
| `begin`/`end` must run on one thread in one synchronous method and nest strictly (LIFO). The instrumented code satisfies this; manual use must too. | `HiltTrace` KDoc. |
| Nothing records unless a driver enables the category. Without the `START` broadcast the automatic `tracing-wire` driver recorded nothing. | [Tracing](TRACING.md), run on an Android 15 emulator. |
| The application scope is never cancelled automatically; call `ApplicationScopeCloser.close()`. | `CoroutinesModuleUnitTest`. |
| `Provider.suspending` and `Lazy.await` cannot interrupt a `get()` that is already running; cancellation surfaces after it returns. | `BlockingResolutionDemoTest`, `SuspendProviderTest`. |
| A blocking `get()` of a heavy binding occupies a thread of the chosen dispatcher for its whole duration. Pick a dispatcher that can afford it. | Follows from the above; not separately tested. |
| `SuspendLazy` runs the initializer in the context of the caller that holds the lock. | `SuspendLazy` KDoc. |
| `traceCoroutine` writes a suspended coroutine as several thread segments of the same named section. | `TraceCoroutineTest`. |
| `@MainDispatcher` is `Dispatchers.Main`; JVM unit tests need `Dispatchers.setMain` or a replacement module. | `CoroutinesModule`. |

## Release and R8

- Release classes are not instrumented. With minification off the sample's release dex still holds the unused `HiltTrace` classes.
- Behavior of an R8-minified build (shrinking the runtime classes, keep rules for `HiltTrace`) has **not been verified**; the sample builds release with `isMinifyEnabled = false`.

## Publishing state

- The four artifacts and the Gradle plugin marker for `1.0.0-alpha01` are available from Maven Central. The marker is `io.github.santimattius.android.hilt.trace:io.github.santimattius.android.hilt.trace.gradle.plugin`; a marker's group is the plugin id, so it sits under the verified `io.github.santimattius` namespace.
- Instrumented tests run on a device or emulator only; CI compiles and packages them but never runs them.

## Compose preview workaround

With AGP 9.5.0 and the Compose compiler plugin of Kotlin 2.4.21, the sample needs two settings for `:generateDebugComposePreviewRunfiles` (see `app/build.gradle.kts`). The failure is independent of Hilt Trace. Details: [Setup](SETUP.md#application-module).
