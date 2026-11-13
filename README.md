# Android Hilt Extension

[![CI](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml/badge.svg)](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml)
[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Runtime tracing for Hilt on Android, plus coroutine helpers for Hilt apps.

- **Hilt Trace** adds Perfetto trace sections around Hilt object creation and injection in debug builds. A trace then shows which bindings a startup creates, in what order, and how long each takes. It is built on `androidx.tracing`.
- **Coroutine helpers** provide dispatcher and scope qualifiers, suspending adapters for `Provider` and `Lazy`, and a trace section that follows a coroutine.

> [!WARNING]
> **Pre-release (`1.0.0-alpha01`).** The four artifacts are not published to Maven Central yet, so there is no dependency to copy from a public repository. Today the sample app in this repository resolves them locally. See [Setup](docs/SETUP.md#consuming-the-artifacts).

## Quick start

For an application module that already uses Hilt with KSP. The `libs.*` aliases come from this repository's version catalog, and the `project(...)` line only works inside this repository, until the artifacts are published.

1. Apply the plugin and name the module's catalogue:

   ```kotlin
   plugins {
       alias(libs.plugins.android.application)
       alias(libs.plugins.ksp)
       alias(libs.plugins.hilt)
       id("com.santimattius.android.hilt.trace")
   }

   ksp {
       arg("hiltTrace.module", ":app")
   }
   ```

2. Add the dependencies:

   ```kotlin
   dependencies {
       implementation(libs.hilt.android)
       ksp(libs.hilt.compiler)
       implementation(libs.androidx.tracing)
       debugImplementation(libs.androidx.tracing.wire)      // writes trace files; optional
       implementation(project(":hilt-coroutines-runtime"))  // coroutine helpers; optional
   }
   ```

3. Build and run a debug variant, then record a trace: [Tracing: Capture a trace](docs/TRACING.md#capture-a-trace).

Library modules, supported versions and the full explanation are in [Setup](docs/SETUP.md).

## Guarantees

| Guarantee | Scope |
|-----------|-------|
| Sections for Hilt creation (`hilt.create`) and injection (`hilt.inject`) | `debug` and `benchmark` variants of an application module, produced by a bytecode transform of Hilt output. |
| Release builds are not instrumented | Release classes and dex stay byte-identical to a build without the plugin (`ReleaseUnchangedTest`). |
| Nothing is recorded when tracing is off | `HiltTrace.begin` returns `null` for a disabled category; with no driver installed, every category is disabled. |
| The build does not fail because of the instrumentation | An unknown class shape or any error leaves the original bytes and logs one warning. |
| `SuspendLazy`, `Provider.suspending`, `Lazy.await`, `traceCoroutine` | Caching, failure and cancellation contracts are covered by JVM tests. |

What is not covered (uninstrumented bindings, no automatic scope cancellation, unmeasured overhead) is listed in [Limitations](docs/LIMITATIONS.md).

## Modules

All artifacts use the group `com.santimattius.android` and the version `libraryVersion` from `gradle.properties`.

| Artifact | Kind | Purpose |
|----------|------|---------|
| `hilt-trace-gradle-plugin` | Gradle plugin, id `com.santimattius.android.hilt.trace` | Instruments the Hilt-generated classes of the selected variants. |
| `hilt-trace-compiler` | KSP processor | Writes the binding catalogue `META-INF/hilt-trace/<module>.json`. |
| `hilt-trace-runtime` | Android library | `HiltTrace` facade over `androidx.tracing`. Called by instrumented code. |
| `hilt-coroutines-runtime` | Android library | Dispatcher and scope qualifiers, `SuspendProvider`, `SuspendLazy`, `Lazy.await`, `traceCoroutine`. |

The repository also contains `:app` (sample and validation harness) and `build-logic/` (convention plugins). Neither is published.

## Requirements

| Item | Value |
|------|-------|
| `minSdk` | 24 |
| Library `compileSdk` | 35; consumers need 35 or later |
| Validated toolchains | AGP 9.1.1 with Kotlin 2.2.21, and AGP 9.5.0 with Kotlin 2.4.21; Hilt 2.59.2 or 2.60.1; KSP only (no kapt) |

Details and the unverified combinations: [Setup: Supported toolchains](docs/SETUP.md#supported-toolchains).

## Documentation

| Guide | Read it for |
|-------|-------------|
| [Setup](docs/SETUP.md) | Requirements, version compatibility, application and library module configuration, consuming the unpublished artifacts. |
| [Tracing](docs/TRACING.md) | What gets a section, section names, categories, capturing a trace. |
| [Coroutines](docs/COROUTINES.md) | Qualifiers, `SuspendProvider`, `SuspendLazy`, `Lazy.await`, `traceCoroutine`. |
| [Limitations](docs/LIMITATIONS.md) | What is not instrumented, and the known constraints with their evidence. |
| [Verify it works](docs/VERIFY.md) | Test commands, sample app walkthrough, trace capture, troubleshooting. |
| [Publishing](docs/PUBLISHING.md) | Maven Central and CI/CD setup for maintainers. |

## Build and test

```bash
./gradlew :app:testDebugUnitTest            # sample unit tests
./gradlew :app:assembleDebug                # build the sample
./gradlew check                             # all modules, included builds, version alignment
./gradlew :app:connectedDebugAndroidTest    # needs a device or emulator; CI only compiles these
```

## Repository layout

```
android-hilt-extension/
├── app/                        # Sample and validation harness (not published)
├── build-logic/                # Included build: convention plugins (not published)
├── compat/                     # Consumer compatibility check used by CI
├── hilt-trace-runtime/         # HiltTrace facade
├── hilt-trace-compiler/        # KSP catalogue processor
├── hilt-trace-gradle-plugin/   # Included build: Gradle plugin
├── hilt-coroutines-runtime/    # Coroutine helpers
├── gradle/libs.versions.toml   # Version catalog
├── docs/                       # Guides
└── gradle.properties           # SDK levels, library coordinates, POM overrides
```

## Contributing

1. Fork the repository and create a feature branch.
2. Write the failing test first, then the code, and keep `./gradlew check` green.
3. Use conventional commit messages.
4. Open a pull request that describes the change.

Releases are published by CI when a GitHub Release is created; see [Publishing](docs/PUBLISHING.md).

## License

[Apache License 2.0](LICENSE). Questions and bug reports: [Issues](https://github.com/santimattius/android-hilt-extension/issues).
