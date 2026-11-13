# Setup

## Requirements

| Item | Version used by this repository | Source |
|------|---------------------------------|--------|
| Android Gradle Plugin | 9.5.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 | `gradle/libs.versions.toml` |
| KSP | 2.3.13 (KSP only, no kapt) | `gradle/libs.versions.toml` |
| Hilt / Dagger | 2.60.1 | `gradle/libs.versions.toml` |
| androidx.tracing | 2.0.5 | `gradle/libs.versions.toml` |
| kotlinx-coroutines | 1.11.0 | `gradle/libs.versions.toml` |
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | 25 runs the build; library bytecode targets 17 | `gradle/gradle-daemon-jvm.properties`, `build-logic` |
| `minSdk` / `compileSdk` | 24 / 37 | `gradle.properties` |

Compatibility beyond this set is described in [Supported toolchains](#supported-toolchains); anything not listed there has not been tested. Two couplings matter:

- **Hilt version.** The plugin rewrites the shape of classes that Hilt generates (`SwitchingProvider`, `Dagger*_HiltComponents_*CImpl`). The rewrite was verified against Hilt 2.60.1 output only. On another Hilt version an unrecognised shape is left unchanged with a warning, so you lose sections rather than the build.
- **androidx.tracing 2.x.** `hilt-trace-runtime` exposes `androidx.tracing:tracing` 2.0.5 as an `api` dependency. Tracing 1.x and 2.x share one coordinate, so Gradle upgrades a consumer that resolves 1.x.

## Supported toolchains

The artifacts are compiled with Kotlin language and API level 2.2 and their POMs depend on `kotlin-stdlib` 2.2.21, so a consumer does not need the Kotlin version this repository builds with. The `compat` CI job builds a minimal app against locally published artifacts and checks the debug dex for instrumentation and the release dex for none.

| Consumer toolchain | Status |
|--------------------|--------|
| AGP 9.1.1, Kotlin 2.2.21, KSP 2.3.13, Hilt 2.59.2 | Validated: debug and release build, debug instrumented, release not. |
| AGP 9.1.1, Kotlin 2.2.21, KSP 2.3.13, Hilt 2.60.1 | Validated, same result. |
| AGP 9.5.0, Kotlin 2.4.21, Hilt 2.60.1 | This repository's own build (the sample app). |
| Anything below AGP 9.1.1 or Kotlin 2.2.21, other KSP or Hilt versions | Unverified. |

- **Floor.** AGP 9.1.1 and Kotlin 2.2.21 are the lowest versions that were tried, not a proven minimum.
- **Hilt and Kotlin metadata.** Hilt 2.59.2 reads Kotlin metadata up to 2.3.0, so it works with consumers up to Kotlin 2.3. A Kotlin 2.4 consumer needs Hilt 2.60.1. This comes from Hilt, not from these artifacts.
- **KSP.** KSP 2.3.x is decoupled from the Kotlin version, so the same KSP release was used with Kotlin 2.2.21 and 2.4.21.
- **compileSdk.** `hilt-trace-runtime` requires consumers to compile against Android 37 or later.

## Application module

The plugin applies to the application module only. It adds `hilt-trace-compiler` to the `ksp` configuration and `hilt-trace-runtime` to `implementation` itself, at the plugin's own version.

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.santimattius.android.hilt.trace")
}

ksp {
    // Names this module's catalogue file; set it, or a hash-based name is generated with a warning.
    arg("hiltTrace.module", ":app")
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // tracing-wire does not expose androidx.tracing on the compile classpath, so declare it explicitly.
    implementation(libs.androidx.tracing)
    // Writes Perfetto files; optional, see docs/TRACING.md.
    debugImplementation(libs.androidx.tracing.wire)
}
```

Optional plugin settings (defaults shown, from `HiltTraceExtension`):

```kotlin
hiltTrace {
    enabled.set(true)                              // false: no variant is instrumented
    variants.set(listOf("debug", "benchmark"))     // release is never part of the default
}
```

If you use the Compose compiler plugin with AGP 9.5.0 and Kotlin 2.4.21, the sample needed a workaround for `:generateDebugComposePreviewRunfiles`; see `app/build.gradle.kts` (`unitTests.isIncludeAndroidResources` and `HasUnitTestBuilder.enableUnitTest`). The failure is independent of Hilt Trace.

## Library module with Hilt code

A library's bindings, entry points and `@AndroidEntryPoint` classes reach the trace only if the library writes a catalogue. The app's plugin reads the catalogues of its runtime classpath (jars and class directories) at build time.

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    arg("hiltTrace.module", ":lib")   // file name: META-INF/hilt-trace/_lib.json
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp("com.santimattius.android:hilt-trace-compiler:1.0.0-alpha01")
}
```

Do not apply the Gradle plugin to a library. Without the `ksp(...)` line the build still passes and the library's entry points are not instrumented. Ids that differ only by `:` versus `_` (`:a:b`, `:a_b`) write the same file name.

Evidence: `LibraryInstrumentationTest` builds a throwaway app with a library subproject using a hand-written class shaped like a generated provider. No test builds a real Hilt-processed library (see [Limitations](LIMITATIONS.md)).

## Coroutine helpers

```kotlin
dependencies {
    implementation("com.santimattius.android:hilt-coroutines-runtime:1.0.0-alpha01")
}
```

The module brings `kotlinx-coroutines-core` and `hilt-android` as `api` dependencies. The app still needs Hilt and its compiler set up as above. Usage: [Coroutines](COROUTINES.md).

## Consuming the artifacts

| Situation | How |
|-----------|-----|
| Before publication (this repository) | `settings.gradle.kts` uses `pluginManagement { includeBuild("hilt-trace-gradle-plugin") }`. `app/build.gradle.kts` substitutes `com.santimattius.android:hilt-trace-runtime` and `:hilt-trace-compiler` with the local projects, because the plugin adds them by coordinate. `:hilt-coroutines-runtime` is a plain `project(...)` dependency. |
| Another project, before publication | Not documented by this repository. A composite build of this repository with the same substitutions is the likely route; it has not been tried. |
| After publication | Coordinates are `com.santimattius.android:<artifact>:<version>` (see the README table). Publishing is described in [Publishing](PUBLISHING.md). Whether the plugin marker artifact is published for plugin id resolution has not been verified. |

`libraryVersion` is `1.0.0-alpha01` in `gradle.properties`.
