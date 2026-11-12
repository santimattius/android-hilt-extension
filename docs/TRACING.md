# Hilt Trace: tracing guide

Hilt Trace adds Perfetto slices around Hilt object creation and injection in debug builds, so a trace shows which bindings a startup creates, in what order, and how long each takes. Status: `1.0.0-alpha01`, not published yet (see the [README](../README.md)).

## How it works

1. The KSP processor in `hilt-trace-compiler` writes a catalogue of the module's Dagger and Hilt bindings, entry points and injection targets to `META-INF/hilt-trace/<module>.json`.
2. The Gradle plugin `com.santimattius.android.hilt.trace` registers an ASM transform for the selected variants (`debug` and `benchmark` by default). It reads the catalogues of the app and of its dependencies.
3. The transform wraps the cases of the generated Hilt `SwitchingProvider` classes (category `hilt.create`) and the generated component methods that inject an `@AndroidEntryPoint` or implement an `@EntryPoint` accessor (category `hilt.inject`). Slice names come from the catalogue: `Type` or `Type@Qualifier`.
4. The wrapped code calls the `HiltTrace` facade of `hilt-trace-runtime`, a thin layer over `Tracer.global` of `androidx.tracing` 2.0.
5. When the category is disabled, or no driver is installed, `HiltTrace.begin` returns `null` and nothing is recorded.

## Categories

| Category | Covers |
|----------|--------|
| `hilt.create` | The creation of a binding inside a generated `SwitchingProvider` case. |
| `hilt.inject` | Member injection of an `@AndroidEntryPoint` (slice `<Type>.members`) and `@EntryPoint` accessor methods (slice `Component.method`). |

Each category is gated by the tracer through `Tracer.isCategoryEnabled`, so the two switch on and off independently. `TraceCaptureTest` checks both gates in the sample app. To choose categories in your own app, provide a driver as the [Tracing 2.0 guide](https://developer.android.com/topic/performance/tracing/in-process-tracing) describes (this snippet follows that page and has not been run in the sample):

```kotlin
class App : Application(), AbstractTraceDriver.Factory {
    override fun create(): AbstractTraceDriver {
        val sink = TraceSink(context = this, fileProvider = { File(noBackupFilesDir, "traces") })
        return TraceDriver(context = this, sink = sink, isCategoryEnabled = { it.startsWith("hilt.") })
    }
}
```

## Capture a trace

`tracing-wire` initializes itself through `androidx.startup`; the sample adds it with `debugImplementation(libs.androidx.tracing.wire)`. By default it writes Perfetto files to `Context.noBackupFilesDir/perfetto_traces/` (official guide). These steps were run on an Android 15 emulator with the sample app:

```bash
PKG=com.santimattius.android.sample
RECEIVER=$PKG/androidx.tracing.profiler.ConnectedProfilerTracingReceiver
./gradlew :app:installDebug
adb shell am broadcast -a androidx.tracing.profiler.action.START -n $RECEIVER
adb shell am force-stop $PKG    # the START setting is read when the process starts
adb shell am start -n $PKG/.MainActivity
adb shell am broadcast -a androidx.tracing.profiler.action.FLUSH_TRACES_GET_PATH -n $RECEIVER
adb pull /storage/emulated/0/Android/media/$PKG/perfetto_traces/<file>.perfetto-trace .
adb shell am broadcast -a androidx.tracing.profiler.action.STOP -n $RECEIVER
```

Then open the file in the [Perfetto UI](https://ui.perfetto.dev/). In the sample the capture holds `hilt.inject` `MainActivity.members`, `hilt.create` `...greeting.GreetingRepository` and `hilt.create` `kotlin.String@javax.inject.Named(value=audience)`.

Verification notes:

- The `START`, `FLUSH_TRACES_GET_PATH` and `STOP` broadcast actions come from the `tracing-wire` 2.0.5 manifest and were observed working. The official page does not describe them.
- Without `START` the automatic driver recorded nothing: the file stayed at 61 bytes (a header), and the `hilt.*` category gate reads the state `START` sets. Provide your own driver (above) if you do not use that mechanism.
- The file stays empty until a flush: the sample file had 0 bytes before the `FLUSH_TRACES_GET_PATH` broadcast.
- `STOP` returns the setting to disabled. Check the plain `adb` steps on your own device and API level; only the emulator above was tried.

## Limitations

These six are not instrumented (spec `hilt-trace-instrumentation`, confirmed against Hilt 2.60.1 output):

1. Unscoped bindings built inline inside component accessors are covered only by the accessor span. This holds only while nothing requests a `Provider<T>` of that binding; if something does, the binding has a `SwitchingProvider` case and gets a slice.
2. `@Binds` and multibindings have no `SwitchingProvider` case.
3. The router `get()` of a `SwitchingProvider` is not traced.
4. `DoubleCheck` cache hits: only the first creation of a scoped binding is traced.
5. Inline constructor-argument wiring: a case that builds an object with many arguments yields one slice, with no slices for the arguments.
6. `*_MembersInjector.injectMembers` has no caller in Hilt 2.60.1 output, so it is not instrumented. A `MembersInjector<T>` requested through DI is not traced.

## Caveats

| Topic | What to expect |
|-------|----------------|
| Scoped and `Provider`/`Lazy` bindings only | An unscoped binding injected directly is inlined by Dagger: it has no provider class and no `hilt.create` slice, and it appears inside its accessor span. In the sample, `provideAudience` is `@Singleton` for this reason. Qualified names show only for bindings that get a provider class. |
| fastInit | Hilt enables it by default. With `-Pdagger.hilt.fastInit=false` there is no `SwitchingProvider`: the plugin leaves the classes unchanged and logs one warning. |
| Library modules | Apply `ksp(hilt-trace-compiler)` to each library that holds Hilt code, or its entry points are not instrumented. This fails open: the build still passes. |
| Variants | `hiltTrace { variants.set(listOf("debug", "benchmark")) }` is the default. Release is not instrumented. |
| Kill switch | `hiltTrace { enabled.set(false) }` instruments no variant and also removes the catalogue from every variant. |
| Catalogue in APKs | The plugin excludes `META-INF/hilt-trace/**` from variants it does not instrument. AGP's default packaging excludes (`**/_*`) already keep underscore-named files such as `_app.json` and `_lib.json` out of APKs, in debug too. This is harmless: the transform reads the catalogue at build time. |
| Release | Release classes stay unchanged. With minification off, the unused `HiltTrace` runtime classes still ship in the release dex. |
| Fail-open | An unknown class shape, a method near 64 KB, or any error leaves the original bytes and logs one warning. The build does not fail. |
| Colliding module ids | Ids that differ only by `:` versus `_` (`:a:b` and `:a_b`) write the same file name, `_a_b.json`. |

### Compose preview workaround

With AGP 9.5.0 and the Compose compiler plugin of Kotlin 2.4.21, `:generateDebugComposePreviewRunfiles` failed in the sample unless both of these were set. The failure is independent of Hilt Trace. The lines are copied from `app/build.gradle.kts`:

```kotlin
android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        (variantBuilder as? HasUnitTestBuilder)?.enableUnitTest = true
    }
}
```

`HasUnitTestBuilder` comes from `com.android.build.api.variant`.

### androidx.tracing 1.x and 2.x

Tracing 1.x and 2.x share the coordinate `androidx.tracing:tracing`. `hilt-trace-runtime` exposes 2.0.5 as an `api` dependency, so a consumer that resolves 1.x gets upgraded to 2.0.5 by Gradle's conflict resolution. This repository sets `android.dependency.useConstraints=true`. AGP 9.0 made `false` the default, which uses constraints between configurations only for Android tests (AGP 9.0 release notes); `true` restores the 8.13 behavior. In the spike, with `true`, the androidTest classpath was pinned to 1.2.0 and 2.0.5 failed to resolve until it was declared in the main `implementation` configuration, as the sample does.

## Tests and CI

| Level | What runs |
|-------|-----------|
| Catalogue processor | JVM tests that compile real Dagger/Hilt annotations with KSP (kctfork). |
| Instrumentation | ASM tests on classes captured from real Hilt 2.60.1 output, with a recording stand-in for `HiltTrace`. |
| Plugin wiring | Gradle TestKit builds of a throwaway Android app: variant selection, packaging, release unchanged. |
| Sample app | Unit tests, plus the instrumented `TraceCaptureTest`, which records the slices of the real debug build. |

CI runs on Linux with no emulator. `./gradlew build` schedules the unit tests of every module, the plugin tests and `verifyVersionAlignment`, and CI adds `:app:assembleDebugAndroidTest` and `:hilt-coroutines-runtime:assembleDebugAndroidTest`, so `TraceCaptureTest` and the `hilt-coroutines-runtime` instrumented tests are compiled and packaged but never run there; they were run on an emulator only.

## Verify it works

These commands ran against the sample after `./gradlew :app:assembleDebug :app:assembleRelease`. The class directories hold the output of the plugin's transform task.

```bash
CLASSES=app/build/intermediates/classes
PKG=dirs/com/santimattius/android/sample
SP='DaggerSampleApplication_HiltComponents_SingletonC$SingletonCImpl$SwitchingProvider.class'

# Debug: wrapped cases call the facade (2 begin calls in the sample)
javap -c -p "$CLASSES/debug/transformDebugClassesWithAsm/$PKG/$SP" | grep -c 'HiltTrace.begin'

# Release: no call site (0)
javap -c -p "$CLASSES/release/transformReleaseClassesWithAsm/$PKG/$SP" | grep -c 'HiltTrace'

# Release APK: no catalogue entry (0), but the unused facade classes are still in the dex
unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | grep -c 'hilt-trace'
unzip -p app/build/outputs/apk/release/app-release-unsigned.apk 'classes*.dex' | strings -a | grep -c 'com/santimattius/android/hilt/trace/HiltTrace'
```

The last command prints a number above zero in the sample. For the instrumented test, read the XML in `app/build/outputs/androidTest-results/connected/` (tests above 0, failures 0): the Gradle exit status can be 0 even when the instrumentation run failed.
