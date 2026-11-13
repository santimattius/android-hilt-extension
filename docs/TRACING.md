# Tracing

Hilt Trace adds Perfetto sections around Hilt object creation and injection in the instrumented variants (`debug` and `benchmark` by default). This page explains what gets a section, how categories and drivers decide what is recorded, and how to capture a trace. Prerequisite: [Setup](SETUP.md). Related: [Verify it works](VERIFY.md), [Limitations](LIMITATIONS.md).

**Quick path:** set up the plugin, install a debug build, send the `START` broadcast, relaunch, use the app, flush, pull the file and open it in Perfetto. Commands: [Capture a trace](#capture-a-trace).

## How it works

1. The KSP processor in `hilt-trace-compiler` writes a catalogue of the module's Dagger and Hilt bindings, `@EntryPoint` methods and `@AndroidEntryPoint` classes to `META-INF/hilt-trace/<module>.json`.
2. The Gradle plugin registers an ASM transform for the selected variants. It reads the catalogues of the app and of its dependencies.
3. The transform wraps code that Hilt generates:
   - the cases of `*$SwitchingProvider` classes, category `hilt.create`;
   - the `inject*` methods and catalogued `@EntryPoint` accessors of `Dagger*_HiltComponents_*CImpl` classes, category `hilt.inject`.
4. The wrapped code calls the `HiltTrace` facade of `hilt-trace-runtime`, a thin layer over `Tracer.global` of `androidx.tracing` 2.0.
5. When the category is disabled, or no driver is installed, `HiltTrace.begin` returns `null` and records nothing.

## What gets a section

| Category | Section name | Produced by |
|----------|--------------|-------------|
| `hilt.create` | `Type` or `Type@Qualifier` (qualified type name, e.g. `kotlin.String@javax.inject.Named(value=audience)`) | Creation of a binding in a `SwitchingProvider` case: scoped bindings, and bindings requested through `Provider`/`Lazy`. |
| `hilt.create` | The class of the first constructor or module factory in the case | Same, when the binding has no catalogue entry (for example a library without the processor). Observed: `...di.CoroutinesModule_DefaultDispatcherFactory`. |
| `hilt.inject` | `<SimpleName>.members` | Member injection of an `@AndroidEntryPoint` class: Activity, View, Service and Fragment shapes. |
| `hilt.inject` | `<Component>.<method>` | An `@EntryPoint` accessor method, named after the component it is installed in. |

`@HiltViewModel` creation is a `hilt.create` section named after the ViewModel class (`ViewModelCImpl$SwitchingProvider` is accepted by the filter). Evidence per kind:

| Kind | Evidence |
|------|----------|
| Activity, View, Service injection; ViewModel, singleton, `Provider`, `Lazy` creation | `TraceCaptureTest` on the real debug build of the sample, run on an Android 15 emulator. |
| Fragment injection | ASM tests on a class captured from real Hilt output (`InjectRuleTest`, `FixtureCoverageTest`). The sample has no Fragment, so it is not covered end to end. |

Sections nest by call: the sample's `MainActivity.members` section contains the `hilt.create` section of the repository it injects, which contains the section of the scoped qualified value it requests.

## Categories and drivers

Each category is gated by `Tracer.isCategoryEnabled`, so `hilt.create` and `hilt.inject` switch on and off independently. Your own sections (such as `traceCoroutine`) use categories you choose.

`tracing-wire` initialises itself through `androidx.startup`. The sample adds it with `debugImplementation(libs.androidx.tracing.wire)`. In the sample, with the capture steps below, the automatic driver recorded `hilt.create`, `hilt.inject` and the `sample.coroutine` category used by `traceCoroutine`.

To choose categories yourself, provide a driver as the [Tracing 2.0 guide](https://developer.android.com/topic/performance/tracing/in-process-tracing) describes. This snippet follows that page and has **not been run** in the sample (unverified):

```kotlin
class App : Application(), AbstractTraceDriver.Factory {
    override fun create(): AbstractTraceDriver {
        val sink = TraceSink(context = this, fileProvider = { File(noBackupFilesDir, "traces") })
        return TraceDriver(context = this, sink = sink, isCategoryEnabled = { it.startsWith("hilt.") })
    }
}
```

There is no on/off switch in the library API: tracing is on when a driver enables the category. The build-time switch is `hiltTrace { enabled.set(false) }`, which removes the instrumentation (and the catalogue) from every variant.

## Capture a trace

Run these from the repository root with an emulator or device attached. They use the automatic `tracing-wire` driver; the broadcast actions come from the `tracing-wire` 2.0.5 manifest.

```bash
PKG=io.github.santimattius.android.sample
RECEIVER=$PKG/androidx.tracing.profiler.ConnectedProfilerTracingReceiver
./gradlew :app:installDebug
adb shell am broadcast -a androidx.tracing.profiler.action.START -n $RECEIVER
adb shell am force-stop $PKG        # the START setting is read when the process starts
adb shell am start -n $PKG/.MainActivity
# ... tap the Run buttons you want to see (see docs/VERIFY.md)
adb shell am broadcast -a androidx.tracing.profiler.action.FLUSH_TRACES_GET_PATH -n $RECEIVER
adb shell ls /storage/emulated/0/Android/media/$PKG/perfetto_traces/
adb pull /storage/emulated/0/Android/media/$PKG/perfetto_traces/<file>.perfetto-trace .
adb shell am broadcast -a androidx.tracing.profiler.action.STOP -n $RECEIVER
```

Open the file in the [Perfetto UI](https://ui.perfetto.dev/) and search the slice names.

> [!NOTE]
> These steps were run on an Android 15 emulator only. Check them on your device and API level.

Observed behavior:

- Without `START` the automatic driver recorded nothing (the file stayed at a 61-byte header).
- Files are empty until `FLUSH_TRACES_GET_PATH`. Several files may exist; the newest one holds the run. An older empty file is normal.
- `STOP` returns the setting to disabled.
- Recording with Android Studio's System Trace or a `perfetto` command line instead of the in-process file has not been tried for these sections (unverified). The in-process file above is the verified path.

## Release builds and the catalogue

- Release classes stay unchanged. The plugin excludes `META-INF/hilt-trace/**` from variants it does not instrument. With minification off, the unused `HiltTrace` runtime classes still ship in the release dex.
- AGP's default packaging excludes (`**/_*`) already keep underscore-named catalogue files out of APKs, in debug too. The transform reads the catalogue at build time, so this is harmless.
