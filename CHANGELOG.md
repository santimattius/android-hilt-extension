# Changelog

User-facing changes are listed here. `Unreleased` describes changes since the last published version; it is not a release announcement.

## Unreleased

## 1.0.0-alpha02 — 2026-11-20

Second prerelease. The APIs and instrumentation behavior may still change before a stable release.

### Added

- Opt-in system trace bridge, `HiltTrace.setSystemTraceEnabled(true)` (off by default): each `hilt.create` and `hilt.inject` section is also written to the system trace, so a system-wide Perfetto capture (`record_android_trace -a <package>`) shows it. The label starts with the category, for example `hilt.inject MainActivity.members`; labels over 127 characters are cut at the end, keeping the category. The in-process trace is unchanged. The cost of the bridge is not measured yet. ([#83](https://github.com/santimattius/android-hilt-extension/issues/83), [#94](https://github.com/santimattius/android-hilt-extension/issues/94))
- `Provider.getTraced(name)` and `Lazy.getTraced(name)` in `hilt-coroutines-runtime`: call them at your own call site to wrap a blocking `get()` in a `hilt.resolve` section named by you, so the binding's `hilt.create` slice nests under it. Fail-open; a plain `get()` stays unlinked. ([#84](https://github.com/santimattius/android-hilt-extension/issues/84))
- Opt-in `hiltTrace { traceMembersInjectors.set(true) }` (off by default): wraps `MembersInjector.injectMembers(T)` in a `hilt.inject` section named `<T>.injectMembers`. `@Binds`, multibinding contributions and inline `new X()` have no hook and are documented as not instrumented. ([#70](https://github.com/santimattius/android-hilt-extension/issues/70))
- `@AssistedInject` creation (and the factory behind `@HiltWorker`) is traced: a `hilt.create` section named after the assisted class, with its dependencies nested under it. `@HiltWorker` is not verified end to end. ([#85](https://github.com/santimattius/android-hilt-extension/issues/85))
- Tests on the real Hilt graph of the sample for a `BroadcastReceiver`, `hiltViewModel()` (plain and with `SavedStateHandle`), a nested Fragment and a `DialogFragment`. These shapes were already instrumented; the documentation now cites the evidence. ([#85](https://github.com/santimattius/android-hilt-extension/issues/85))
- Managed-device validation of debug and R8-minified instrumented builds, real Android-library integration tests, Fragment trace capture, benchmark smoke checks, and an API 23 probe. The documented minimum supported API remains 24.
- Checks that instrumented-test reports contain executed, passing tests, preventing false-green Gradle results.
- `:app:verifyMinifiedReleaseUntraced` to check that an R8-minified release contains no tracing call sites. Requires `-PminifyRelease=true`.
- Sample startup A/B measurement tooling and `-PhiltTraceEnabled=false` to build the sample without instrumentation. The recorded single-device median difference was within measurement noise; it is not a zero-overhead guarantee. See [Benchmarks](docs/BENCHMARK.md).
- A weekly consumer compatibility check against the latest stable Hilt, plus a guard keeping the documented toolchains aligned with the compatibility matrix. A successful probe does not automatically expand the supported range.

### Changed

- `Provider.suspending` and `Lazy.await` now open a `hilt.resolve` section around the blocking `get()`, so the `hilt.create` slice of the resolved binding nests under it. With the category disabled they behave as before. ([#84](https://github.com/santimattius/android-hilt-extension/issues/84))
- For assisted factories, the misleading `...SwitchingProvider$N` slice that only timed the allocation of the factory object is gone, replaced by the slice of the assisted class. ([#85](https://github.com/santimattius/android-hilt-extension/issues/85))

### Fixed

- Collect binding catalogues from Android library modules, including the library variant selected by Gradle for the application. Add coverage for flavor fallbacks and configuration-cache reuse.
- Read Android library catalogues from included builds without confusing projects that share the same path. Preserve catalogues when libraries use the same module ID, and warn when a selected variant or conventional included-build directory cannot be read.
- Name fallback creation slices after the constructor or factory producing the returned binding, rather than a nested argument factory. This fixes misleading names such as `CoroutinesModule_DefaultDispatcherFactory` for an application-scope binding. ([#80](https://github.com/santimattius/android-hilt-extension/issues/80), [#81](https://github.com/santimattius/android-hilt-extension/pull/81))
- The limitations documentation said that multibindings have no `SwitchingProvider` case. A scoped contribution, or one requested through a `Provider`, has its own case and a `hilt.create` section. ([#86](https://github.com/santimattius/android-hilt-extension/issues/86))

### Known issues

- A trace-capture integration test can intermittently record an unmatched END and time out. Diagnostics now report surrounding events, tracks and dropped-event counts; the root cause remains unresolved. ([#74](https://github.com/santimattius/android-hilt-extension/issues/74))
- Inline bindings, alias-only `@Binds` bindings and multibinding aggregates retain their documented instrumentation limits. See [Limitations](docs/LIMITATIONS.md).
- Hilt 2.59.2 is verified by the consumer build only, not by slice names, for the assisted, members-injector and multibinding shapes. See [Limitations](docs/LIMITATIONS.md).

## 1.0.0-alpha01 — 2026-11-13

Initial prerelease published to Maven Central.

### Added

- Hilt Trace Gradle plugin, KSP binding catalogue processor and runtime tracing facade.
- `hilt.create` sections for supported `SwitchingProvider` creation paths and `hilt.inject` sections for catalogued component accessors and Android injection methods.
- Debug/benchmark instrumentation by default, release exclusion and fail-open handling for unsupported bytecode shapes.
- Coroutine helpers: dispatcher and application-scope qualifiers, `Provider.suspending`, `SuspendLazy`, `Lazy.await` and `traceCoroutine`.
- Sample application demonstrating injection, deferred resolution, coroutine helpers and simulated dependency-graph costs.
- Setup, tracing, coroutine, verification and Maven Central publishing guides.

[Full comparison since alpha01](https://github.com/santimattius/android-hilt-extension/compare/v1.0.0-alpha01...v1.0.0-alpha02)
