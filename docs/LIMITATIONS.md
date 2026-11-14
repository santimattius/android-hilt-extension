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
| With real Hilt 2.60.1 and KSP, a library module's binding is instrumented in the app's debug dex (`hilt.create`; none in release). For an Android library project the catalogue reaches the app transform: the slice is named `fixture.lib.LibService` (not the bytecode name) and the `@EntryPoint` accessor is traced (`SingletonComponent.libService`) in debug, neither in release. An Android library does not package KSP resources, so the plugin reads the library's `generated/ksp/<variant>/resources` in place and declares the library's `ksp<Variant>Kotlin` task as its producer. | `RealHiltLibraryTest` (run with `./gradlew -p hilt-trace-gradle-plugin test --tests '*RealHiltLibraryTest'`), `LibraryInstrumentationTest`. |
| The Android library variant is matched to the app variant **by name** (`generated/ksp/<app variant>/resources`). A library that applies KSP but has no variant with that name (for example an app-only product flavor, `freeDebug`, against the library's `debug`) is skipped: one warning names the library and the variant, the build passes, and its slices use bytecode names. Libraries are found from declared project dependencies (the runtime classpath is not resolved, to avoid a configuration-time resolution), so only projects of the current build are read: a library from an included build is skipped, and an error while reading the declarations is skipped with a warning. No mapping through the dependency's resolved variant is implemented. | `LibraryVariantTest`. |
| A Fragment is traced as `hilt.inject` `<Fragment>.members`, shown with the sample's `DemoFragment` on an Android 15 emulator and on the CI managed device (API 34). Other Fragment shapes (nested, `DialogFragment`, retained) are covered by ASM tests only. | `FragmentTraceCaptureTest` (instrumented), `InjectRuleTest`. |
| KSP only; there is no kapt path. | Project constraint, `CatalogueProcessor`. |
| `minSdk` 24 for the library modules and the sample. `androidx.tracing` 2.0.5 itself declares minSdk 23 (its AAR manifest), but that is not the library's floor: a consumer with minSdk 23 is rejected by the manifest merger (`uses-sdk:minSdkVersion 23 cannot be smaller than version 24 declared in library hilt-trace-runtime`), observed locally with AGP 9.1.1 / Kotlin 2.2.21 / Hilt 2.60.1, and the `minSdk 23` entry of the compat matrix passed in CI on that expectation. **Not shown:** that the libraries would run on API 23 if the check were overridden (no build or run at that level). | `gradle.properties`, `compat/run.sh` (`COMPAT_EXPECT_MIN_SDK_REJECTION`), `.github/workflows/compat.yml`. |
| Validated versions are listed in [Setup: Supported toolchains](SETUP.md#supported-toolchains). Others are unverified. | `gradle/libs.versions.toml`, `.github/workflows/compat.yml`. |

## Runtime

| Constraint | Evidence |
|------------|----------|
| With a category disabled, `HiltTrace.begin` returns `null` and allocates nothing; the call site still runs in instrumented builds. The cost of that check has **not been measured**: a microbenchmark exists; the `benchmark-smoke` CI job (**pending CI**) only runs it on an emulator to show it executes, and emulator timings are not a measurement. It has not been run on a physical device ([Benchmark](BENCHMARK.md)). | `HiltTraceTest` (disabled-category behavior), `CallPathsTest`; no recorded numbers. |
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
- The sample's `minified` build type (debug-like, instrumented, `isMinifyEnabled = true`) keeps the instrumentation under R8 with no extra keep rules: `./gradlew :app:verifyMinifiedTracing` finds the `hilt.inject`/`hilt.create` categories and the slice names (activity, fragment, view, service, ViewModel) in its dex. R8 inlines `HiltTrace` into the call sites and removes the class, so a keep rule for it is not needed. Without a tracing driver (`tracing-wire`) on that variant R8 dropped the call sites, so a minified build that should record needs the driver. Evidence: `verifyMinifiedTracing`, run once on the sample.
- The instrumented tests can also target the minified build type (`-PtestBuildType=minified`, task `:instrumentedTestsMinifiedCi`, CI job `instrumented-minified`), with test keep rules in `app/proguard-test-rules.pro` that apply only to that run. Observed on the CI managed device (`ci`, API 34, `aosp-atd`), PR #59, job `instrumented-minified`: 30 tests, 0 failures, 0 skipped (`TraceCaptureTest` 13, `MainActivityDemoTest` 13, `DemoScreenTest` 3, `FragmentTraceCaptureTest` 1), so the minified, instrumented APK records its slices at runtime (the slice assertions of `TraceCaptureTest` and `FragmentTraceCaptureTest` pass under R8). It took three CI runs. The first was a **false green**: Gradle finished successfully, but the result XML held a single `testRunFailed` testcase ("Instrumentation run failed due to Process crashed") and no test ran; `scripts/assert-instrumented-results.sh` now fails the job in that case (no result XML, 0 testcases, any failure or error, or a `testRunFailed`). The crash came with the test dex referencing 143 library types (Compose, `androidx.tracing`, Kotlin) that R8 had removed from the app; keeping those packages in the test rules let the 30 tests start. The second run failed 2 tests with `NoSuchMethodError` (`FragmentManager.findFragmentByTag`, `DemoSpec.getId`), fixed by keeping `androidx.fragment` and `DemoSpec`. **Limits:** the test rules keep whole packages, so this shows that the instrumented call sites record under R8, not how much R8 shrinks a production build; the run is on one image (API 34); `hilt-coroutines-runtime` is not minified.
- **Not verified:** any minified release build; the sample's release stays `isMinifyEnabled = false` and uninstrumented, and `verifyMinifiedTracing` does not touch it.

## Publishing state

- The four artifacts and the Gradle plugin marker for `1.0.0-alpha01` are available from Maven Central. The marker is `io.github.santimattius.android.hilt.trace:io.github.santimattius.android.hilt.trace.gradle.plugin`; a marker's group is the plugin id, so it sits under the verified `io.github.santimattius` namespace.
- Instrumented tests need a device or emulator. The `instrumented` CI job runs them on a Gradle Managed Device (`./gradlew instrumentedTestsCi`); its first CI run passed on PR #56: 34 tests, 0 failures, 0 skipped on the `ci` device (API 34, `aosp-atd`), including `FragmentTraceCaptureTest`. The `build` job compiles and packages them. **Known flake:** `TraceCaptureTest.complexGraphShowsTheChain…` timed out twice in about fifteen managed-device CI runs on PRs #59/#60 (once in the minified job, once in the debug job; it passed on a rerun with the same code). The logcat of the debug failure shows the emulator stalling, not slices going missing: `MainActivity` reaches `RESUMED`, then `Sync transaction timed out waiting for commit callback` and about 16 s without a single log line, so the test's wait ran out. Raising the wait to 30 s did not prevent it (the failing run already had it), so it was reverted; a failed job of this kind is expected to pass on a rerun. The results guard still fails the job in that case, which is intended: a failed test is never reported as a pass.

## Compose preview workaround

With AGP 9.5.0 and the Compose compiler plugin of Kotlin 2.4.21, the sample needs two settings for `:generateDebugComposePreviewRunfiles` (see `app/build.gradle.kts`). The failure is independent of Hilt Trace. Each half is checked to still be required: dropping either one (`-PdisableComposePreviewWorkaround=resources|unit-tests`) makes AGP 9.5.0 fail creating the task, while the control with both in place does not. `app/scripts/check-compose-preview-canary.sh` (CI job `compose-preview-canary`) exits 1 when a dropped half no longer fails, so the workaround can be removed. Observed locally on AGP 9.5.0 / Kotlin 2.4.21, and the CI job passed on PR #56. Details: [Setup](SETUP.md#application-module).
