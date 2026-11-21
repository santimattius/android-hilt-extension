# Limitations and known constraints

What Hilt Trace and the coroutine helpers do not cover, and which claims lack proof. Items marked **Unverified** are expected from the design but not backed by a test or run in this repository.
Proof for each item (tests, spikes, measurements, CI runs) is in [EVIDENCE.md](EVIDENCE.md). Related: [Setup](SETUP.md), [Tracing](TRACING.md), [Coroutines](COROUTINES.md).

Version scope: shapes below were checked against Hilt 2.60.1. Hilt 2.59.2 is supported, but unverified for the listed shapes.

## What is not traced

### Unscoped bindings built inline in an accessor

Dagger inlines them, so only the component accessor section covers them. Make the binding scoped (for example `@Singleton`) or request it as `Provider<T>`/`Lazy<T>` to get a `hilt.create` section.
Evidence: [EVIDENCE.md#unscoped-inline-bindings](EVIDENCE.md#unscoped-inline-bindings)

### `@Binds`

Dagger generates no factory or call site for `@Binds`, so there is nothing to hook. Status: Not planned. Not checked on Hilt 2.59.2.
Evidence: [EVIDENCE.md#binds](EVIDENCE.md#binds)

### Multibindings (`@IntoSet`, `@IntoMap`) and the aggregate `Set`/`Map`

The aggregate has no code of its own and an unscoped contribution is inlined, so neither gets a section. Status: Not planned.
A scoped contribution, or one requested as a `Provider`, is already traced as `hilt.create`. Behavior with `fastInit=false` was not checked.
Evidence: [EVIDENCE.md#multibindings](EVIDENCE.md#multibindings)

### A span around an inline `new X()`

A per-binding span here cannot be made safe: it risks a bytecode verifier hazard and a leaked span when the constructor throws. Status: Not feasible.
Evidence: [EVIDENCE.md#inline-constructor-span](EVIDENCE.md#inline-constructor-span)

### The static `provideX` call inside an accessor

It is not wrapped, because the accessor section already covers it. Status: Not planned.
Evidence: [EVIDENCE.md#static-provide-call](EVIDENCE.md#static-provide-call)

### The router `get()` of a `SwitchingProvider`

It is not traced.
Evidence: [EVIDENCE.md#switching-provider-router](EVIDENCE.md#switching-provider-router)

### A plain `Provider.get()` / `Lazy.get()` is not linked to its requester

Generated code cannot identify the caller, so the `hilt.create` slice of a plain `get()` is a root slice on the thread that ran it.
Call `getTraced(name)` at your call site to open a linked `hilt.resolve` section; `Provider.suspending` and `Lazy.await` are linked the same way.
Evidence: [EVIDENCE.md#provider-get-linking](EVIDENCE.md#provider-get-linking)

### `DoubleCheck` cache hits

Only the first creation of a scoped binding has a section.
Evidence: [EVIDENCE.md#doublecheck-cache-hits](EVIDENCE.md#doublecheck-cache-hits)

### Constructor arguments of a created object

There is one section per created object and none for its arguments.
Evidence: [EVIDENCE.md#constructor-arguments](EVIDENCE.md#constructor-arguments)

### `*_MembersInjector.injectMembers`

Status: Opt-in, off by default (see [Tracing](TRACING.md#members-injectors-opt-in)). Only code that receives a `MembersInjector<T>` through DI and calls it records a section.
Injection done by the component and `@AndroidEntryPoint` classes are unaffected. Unverified on Hilt 2.59.2.
Evidence: [EVIDENCE.md#members-injectors](EVIDENCE.md#members-injectors)

### Release variants

Release classes are unchanged by design.
Evidence: [EVIDENCE.md#release-variants](EVIDENCE.md#release-variants)

## Build and environment

### Application module only

The plugin registers on `com.android.application`.
Evidence: [EVIDENCE.md#application-module-only](EVIDENCE.md#application-module-only)

### Hilt `fastInit` must stay on

With `-Pdagger.hilt.fastInit=false` Hilt generates no `SwitchingProvider`, so creation sections are skipped and one warning is logged.
Evidence: [EVIDENCE.md#fast-init](EVIDENCE.md#fast-init)

### Coupling to the shape of Hilt-generated classes

An unknown shape, a method near 64 KB, or any error leaves the class unchanged with one warning. The supported range is the Hilt versions in [Supported toolchains](SETUP.md#supported-toolchains).
A new Hilt release can change a shape and go unnoticed for up to a week or a month, and a red compatibility run does not catch every shape change.
Evidence: [EVIDENCE.md#generated-shape-coupling](EVIDENCE.md#generated-shape-coupling)

### Hilt 2.59.2 is unverified for several shapes

Multibindings, `@Binds`, assisted creation, members injectors and the accessor section are Unverified on 2.59.2. The compatibility build only proves that the build, transform and dex check run and that something is instrumented.
Evidence: [EVIDENCE.md#hilt-2-59-2](EVIDENCE.md#hilt-2-59-2)

### Other versions

Validated versions are listed in [Supported toolchains](SETUP.md#supported-toolchains). Others are Unverified.
Evidence: [EVIDENCE.md#validated-versions](EVIDENCE.md#validated-versions)

### KSP only

There is no kapt path.
Evidence: [EVIDENCE.md#ksp-only](EVIDENCE.md#ksp-only)

### `minSdk` 24

The library modules require `minSdk` 24, and a consumer with a lower `minSdk` is rejected by the manifest merger. Status: Not supported; an API 23 probe exists only as evidence.
Evidence: [EVIDENCE.md#min-sdk](EVIDENCE.md#min-sdk)

### Library modules need setup

A library needs `ksp(hilt-trace-compiler)` and `hiltTrace.module`, or its entry points are not instrumented. The build passes either way.
Evidence: [EVIDENCE.md#library-modules](EVIDENCE.md#library-modules)

### Library catalogue and variant selection

Library bindings are instrumented in the app's debug dex, and the plugin reads the library variant Gradle selected for the app variant. Status: Supported for the verified shapes.
Edge cases (relocated build directories, nested included builds, same `hiltTrace.module` in two libraries) skip or rename a catalogue with one warning. Flavored libraries rely on an internal AGP attribute, and older AGP versions are out of scope.
Evidence: [EVIDENCE.md#library-real-hilt](EVIDENCE.md#library-real-hilt), [EVIDENCE.md#library-variant-selection](EVIDENCE.md#library-variant-selection)

### Android entry points

Fragments (including nested and `DialogFragment`), `@AndroidEntryPoint` receivers and `hiltViewModel()` are traced and shown on an emulator. Status: Supported.
Navigation-scoped ViewModels are Unverified. A retained `@AndroidEntryPoint` Fragment is not supported by Hilt itself.
Evidence: [EVIDENCE.md#android-entry-points](EVIDENCE.md#android-entry-points)

### Assisted creation

`@AssistedInject` creation is traced as `hilt.create` named after the assisted class. Any other generated shape is left unchanged with one warning.
`@HiltWorker` uses the same shape but is Unverified end to end.
Evidence: [EVIDENCE.md#assisted-creation](EVIDENCE.md#assisted-creation)

## Runtime behavior

### Cost with a category disabled

`HiltTrace.begin` returns `null` and allocates nothing, but the call site still runs. One manual measurement on one device showed roughly 20 ns of extra cost per traced call and no cold-start change beyond noise.
Treat this as an indication, not a guarantee. Emulator timings are not a measurement.
Evidence: [EVIDENCE.md#runtime-overhead](EVIDENCE.md#runtime-overhead)

### `begin`/`end` nesting

Both must run on one thread in one synchronous method and nest strictly. Instrumented code satisfies this; manual use must too.
Evidence: [EVIDENCE.md#begin-end-nesting](EVIDENCE.md#begin-end-nesting)

### System trace bridge

Status: Opt-in, off by default (`HiltTrace.setSystemTraceEnabled(true)`). Its cost with the bridge on is not yet measured on a physical device.
The system label is `<category> <name>`, cut at 127 characters on the name side.
Evidence: [EVIDENCE.md#system-trace-bridge](EVIDENCE.md#system-trace-bridge)

### A driver must enable the category

Nothing records unless a driver enables the category. Without the `START` broadcast the automatic `tracing-wire` driver recorded nothing.
Evidence: [EVIDENCE.md#category-drivers](EVIDENCE.md#category-drivers)

### Application scope is never cancelled automatically

Call `ApplicationScopeCloser.close()` when you want it cancelled.
Evidence: [EVIDENCE.md#application-scope](EVIDENCE.md#application-scope)

### Cancellation does not interrupt a running `get()`

`Provider.suspending` and `Lazy.await` cannot interrupt a `get()` that is already running; cancellation surfaces after it returns.
Evidence: [EVIDENCE.md#suspending-cancellation](EVIDENCE.md#suspending-cancellation)

### A blocking `get()` occupies a dispatcher thread

A heavy binding holds a thread of the chosen dispatcher for its whole duration, so pick a dispatcher that can afford it. This follows from the item above and has no dedicated test.
Evidence: [EVIDENCE.md#blocking-get-dispatcher](EVIDENCE.md#blocking-get-dispatcher)

### `SuspendLazy` initializer context

The initializer runs in the context of the caller that holds the lock.
Evidence: [EVIDENCE.md#suspend-lazy-context](EVIDENCE.md#suspend-lazy-context)

### `traceCoroutine` segments

A suspended coroutine is written as several thread segments of the same named section.
Evidence: [EVIDENCE.md#trace-coroutine-segments](EVIDENCE.md#trace-coroutine-segments)

### `@MainDispatcher` in unit tests

`@MainDispatcher` is `Dispatchers.Main`, so JVM unit tests need `Dispatchers.setMain` or a replacement module.
Evidence: [EVIDENCE.md#main-dispatcher](EVIDENCE.md#main-dispatcher)

## Release and R8

- Release classes are not instrumented. With minification off, the sample's release dex still holds the unused `HiltTrace` classes.
- The instrumented `minified` build type keeps its instrumentation under R8 with no extra keep rules. A minified build that should record still needs a tracing driver such as `tracing-wire`.
- Instrumented tests also record their slices against the minified build type, using test-only keep rules. This shows call sites record under R8, not how much R8 shrinks a production build.
- A minified release stays uninstrumented, checked on the sample by a dex check (`:app:verifyMinifiedReleaseUntraced`). Status: Supported for the sample only; this is not a runtime measurement.
- Unverified: a consumer app with its own R8 rules, other devices or API levels, and the run-time absence of slices in release. Nothing is claimed beyond the sample's dex check and one manual device run.

Evidence: [EVIDENCE.md#release-and-r8](EVIDENCE.md#release-and-r8)

## Known issues

### Intermittent instrumented-test timeout in CI

A complex-graph capture test occasionally times out on the managed device and passes on rerun. This affects the project's CI, not adopters. The cause is partly understood and the origin is not established.
Evidence: [EVIDENCE.md#known-flake](EVIDENCE.md#known-flake)

### Publishing state

The four artifacts and the Gradle plugin marker for `1.0.0-alpha01` are available from Maven Central. Instrumented tests need a device or emulator.
Evidence: [EVIDENCE.md#publishing-state](EVIDENCE.md#publishing-state)

### Compose preview workaround in the sample

With AGP 9.5.0 and the Compose compiler plugin of Kotlin 2.4.21, the sample needs two settings for `:generateDebugComposePreviewRunfiles`. The failure is independent of Hilt Trace; see [Setup](SETUP.md#application-module).
Evidence: [EVIDENCE.md#compose-preview-workaround](EVIDENCE.md#compose-preview-workaround)
