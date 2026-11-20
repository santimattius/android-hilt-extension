# Changelog

User-facing changes are listed here. `Unreleased` describes changes since the last published version; it is not a release announcement.

## Unreleased

### Fixed

- Collect binding catalogues from Android library modules, including the library variant selected by Gradle for the application. Add coverage for flavor fallbacks and configuration-cache reuse.
- Read Android library catalogues from included builds without confusing projects that share the same path. Preserve catalogues when libraries use the same module ID, and warn when a selected variant or conventional included-build directory cannot be read.
- Name fallback creation slices after the constructor or factory producing the returned binding, rather than a nested argument factory. This fixes misleading names such as `CoroutinesModule_DefaultDispatcherFactory` for an application-scope binding. ([#80](https://github.com/santimattius/android-hilt-extension/issues/80), [#81](https://github.com/santimattius/android-hilt-extension/pull/81))

### Changed

- The system trace label of the opt-in bridge (`HiltTrace.setSystemTraceEnabled(true)`) now starts with the category, for example `hilt.inject MainActivity.members` instead of `MainActivity.members`, so the sections are easy to find in a system-wide Perfetto capture. Labels longer than 127 characters are cut at the end, keeping the category. The in-process trace is unchanged. ([#83](https://github.com/santimattius/android-hilt-extension/issues/83))

### Added

- Opt-in `hiltTrace { traceMembersInjectors.set(true) }` (off by default): wraps `MembersInjector.injectMembers(T)` in a `hilt.inject` section named `<T>.injectMembers`. `@Binds`, multibinding contributions and inline `new X()` have no hook and are documented as not instrumented. ([#70](https://github.com/santimattius/android-hilt-extension/issues/70))
- Managed-device validation of debug and R8-minified instrumented builds, real Android-library integration tests, Fragment trace capture, benchmark smoke checks, and an API 23 probe. The documented minimum supported API remains 24.
- Checks that instrumented-test reports contain executed, passing tests, preventing false-green Gradle results.
- `:app:verifyMinifiedReleaseUntraced` to check that an R8-minified release contains no tracing call sites. Requires `-PminifyRelease=true`.
- Sample startup A/B measurement tooling and `-PhiltTraceEnabled=false` to build the sample without instrumentation. The recorded single-device median difference was within measurement noise; it is not a zero-overhead guarantee. See [Benchmarks](docs/BENCHMARK.md).
- A weekly consumer compatibility check against the latest stable Hilt, plus a guard keeping the documented toolchains aligned with the compatibility matrix. A successful probe does not automatically expand the supported range.

### Known issues

- A trace-capture integration test can intermittently record an unmatched END and time out. Diagnostics now report surrounding events, tracks and dropped-event counts; the root cause remains unresolved. ([#74](https://github.com/santimattius/android-hilt-extension/issues/74))
- Inline bindings, alias-only `@Binds` bindings, multibinding aggregates and direct `MembersInjector` calls retain their documented instrumentation limits. See [Limitations](docs/LIMITATIONS.md).

## 1.0.0-alpha01 — 2026-11-13

Initial prerelease published to Maven Central.

### Added

- Hilt Trace Gradle plugin, KSP binding catalogue processor and runtime tracing facade.
- `hilt.create` sections for supported `SwitchingProvider` creation paths and `hilt.inject` sections for catalogued component accessors and Android injection methods.
- Debug/benchmark instrumentation by default, release exclusion and fail-open handling for unsupported bytecode shapes.
- Coroutine helpers: dispatcher and application-scope qualifiers, `Provider.suspending`, `SuspendLazy`, `Lazy.await` and `traceCoroutine`.
- Sample application demonstrating injection, deferred resolution, coroutine helpers and simulated dependency-graph costs.
- Setup, tracing, coroutine, verification and Maven Central publishing guides.

[Full comparison since alpha01](https://github.com/santimattius/android-hilt-extension/compare/v1.0.0-alpha01...main)
