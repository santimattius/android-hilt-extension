# Android Hilt Extension

[![Maven Central](https://img.shields.io/maven-central/v/com.santimattius.android/hilt-extension?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/com.santimattius.android/hilt-extension)
[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CI](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml/badge.svg)](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml)

Perfetto tracing for Hilt object creation and injection, plus coroutine helpers for Hilt (suspending providers, dispatcher qualifiers, an application scope).

**Status: alpha (`1.0.0-alpha01`).** Nothing from this change has been published to Maven Central yet. The sample app in this repository resolves the artifacts below locally, by Gradle dependency substitution. The earlier `com.santimattius.android:hilt-extension:1.0.0-alpha01` artifact stays on Maven Central (that is what the badge above shows).

---

## Modules

All artifacts use the group `com.santimattius.android` and the version from `gradle.properties` (`libraryVersion`).

| Artifact | Kind | Purpose |
|----------|------|---------|
| `hilt-trace-runtime` | Android library | `HiltTrace` facade over `androidx.tracing` 2.0.5 (exposed as `api`). Called by instrumented code. |
| `hilt-trace-compiler` | KSP processor | Writes the binding catalogue `META-INF/hilt-trace/<module>.json`. |
| `hilt-trace-gradle-plugin` | Gradle plugin, id `com.santimattius.android.hilt.trace` | Instruments the generated Hilt classes of debug builds. |
| `hilt-coroutines-runtime` | Android library | `SuspendLazy`, `SuspendProvider`, dispatcher qualifiers, `@ApplicationScope`, `traceCoroutine`. |

Other directories: `:app` (sample, not published), `build-logic/` (convention plugins, not published) and `spike/` (throwaway probes of the first slice, not published).

## Requirements

| Item | Version | Source |
|------|---------|--------|
| Android Gradle Plugin | 9.5.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 | `gradle/libs.versions.toml` |
| KSP | 2.3.13 (KSP only, no kapt) | `gradle/libs.versions.toml` |
| Hilt / Dagger | 2.60.1 | `gradle/libs.versions.toml` |
| androidx.tracing | 2.0.5 | `gradle/libs.versions.toml` |
| kotlinx-coroutines | 1.11.0 | `gradle/libs.versions.toml` |
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | 25 runs the build (`gradle/gradle-daemon-jvm.properties`); library bytecode targets 17 | build-logic |
| `minSdk` / `compileSdk` | 24 / 37 | `gradle.properties` |

---

## Quick start

### App module

The plugin adds `hilt-trace-compiler` to `ksp` and `hilt-trace-runtime` to `implementation` itself. This is the sample's `app/build.gradle.kts` reduced to the relevant lines:

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

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // tracing-wire does not expose androidx.tracing on the compile classpath, so declare it explicitly.
    implementation(libs.androidx.tracing)
    debugImplementation(libs.androidx.tracing.wire)
}
```

In this repository the plugin comes from the included build `hilt-trace-gradle-plugin` and the two unpublished artifacts resolve to local projects (`dependencySubstitution` in `app/build.gradle.kts`). Outside this repository you need published artifacts, which do not exist yet.

### Library module with Hilt code

Apply the processor so the library's bindings reach the catalogue (copied from the `spike/lib` probe; the dependency is declared as a coordinate here because the artifact is not published):

```kotlin
ksp {
    arg("hiltTrace.module", ":lib")
}

dependencies {
    ksp("com.santimattius.android:hilt-trace-compiler:1.0.0-alpha01")
}
```

Without it the library's entry points are not instrumented; the build still passes.

### Run the sample

```bash
./gradlew :app:installDebug
```

See [docs/TRACING.md](docs/TRACING.md) for how to capture and read a trace, the categories, and the limitations.

---

## Coroutines module

Add `hilt-coroutines-runtime` to an app that uses Hilt (it brings `kotlinx-coroutines-core` and `hilt-android` as `api`). Everything below is in the packages `com.santimattius.android.hilt.coroutines`, `.suspend` and `.di`.

```kotlin
// A value whose initializer suspends; the result is cached, failures are not.
val settings = SuspendLazy { loadSettings() }
val value = settings.get()

// javax.inject.Provider as a SuspendProvider: get() runs on the dispatcher you pass.
class Repo @Inject constructor(
    parser: Provider<Parser>,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val parsers = parser.suspending(io)
    suspend fun parse() = parsers.get()
}

// dagger.Lazy without blocking the caller (default dispatcher: Dispatchers.Default)
suspend fun warmUp(lazy: Lazy<Cache>) = lazy.await(dispatcher = Dispatchers.Default)

// Trace slice that follows the coroutine across suspension points
suspend fun refresh() = traceCoroutine("app.sync", "refresh") { fetch() }
```

| API | Notes |
|-----|-------|
| `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher` | Qualify a `CoroutineDispatcher` bound by `CoroutinesModule` in `SingletonComponent`. Do not declare your own qualifiers for these types. |
| `@ApplicationScope` | Qualifies a `CoroutineScope` (`SupervisorJob` plus the default dispatcher), `@Singleton`. |
| `ApplicationScopeCloser` | `@Singleton`, `AutoCloseable`. `SingletonComponent` has no destroy callback, so nothing cancels the scope automatically: the owner of the component calls `close()`, for example a test rule. |
| `traceCoroutine` | Passthrough to `androidx.tracing`. A plain call of the block when the category is disabled or no driver is installed. |

Tests can replace the module with `@TestInstallIn(replaces = [CoroutinesModule::class])`.

---

## Project structure

```
android-hilt-extension/
├── app/                        # Sample app (not published)
├── build-logic/                # Included build: convention plugins (not published)
├── hilt-trace-runtime/         # HiltTrace facade
├── hilt-trace-compiler/        # KSP catalogue processor
├── hilt-trace-gradle-plugin/   # Included build: Gradle plugin
├── hilt-coroutines-runtime/    # Coroutine helpers
├── spike/                      # Throwaway probes (not published)
├── gradle/
│   └── libs.versions.toml      # Version catalog
├── .github/workflows/          # ci.yml (build and test), publish-maven-central.yml
├── docs/
│   ├── TRACING.md              # Tracing guide
│   ├── PUBLISHING.md           # Maven Central & CI/CD setup
│   └── spike/                  # Findings of the first slice
└── gradle.properties           # SDK versions, library coordinates, POM overrides
```

---

## Building and testing

```bash
# Assemble debug and release of the sample and run every unit test and check
./gradlew :app:assembleDebug :app:assembleRelease check

# Instrumented tests need a device or emulator (CI only compiles them)
./gradlew :app:connectedDebugAndroidTest
```

---

## Publishing

Releases are published to Maven Central when a [GitHub Release](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-your-repository) is created. The release **tag** defines the version (e.g. tag `v1.0.0` → published version `1.0.0`).

**Prerequisites:** Sonatype Central account, token, and GPG key. Configure the required [GitHub Secrets](.github/workflows/publish-maven-central.yml) and follow the full guide:

**[Publishing to Maven Central](docs/PUBLISHING.md)**

---

## Contributing

Contributions are welcome. Please:

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/your-feature`).
3. Commit your changes with clear messages (`git commit -m 'Add feature X'`).
4. Push to the branch (`git push origin feature/your-feature`).
5. Open a Pull Request and describe your changes.

---

## License

This project is licensed under the **Apache License 2.0**.

---

## Contact

- **Issues:** [GitHub Issues](https://github.com/santimattius/android-hilt-extension/issues)
- **Releases:** [GitHub Releases](https://github.com/santimattius/android-hilt-extension/releases)
