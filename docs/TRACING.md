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
| `hilt.create` | The class of the constructor or module factory that produces the value the case returns (not one of its arguments) | Same, when the binding has no catalogue entry (for example a library without the processor). Observed: `...di.CoroutinesModule_ApplicationScopeFactory`. |
| `hilt.create` | `Type` of the assisted class (the catalogue name of its `ASSISTED_INJECT` entry, else the constructed class) | Construction of an `@AssistedInject` class through its `@AssistedFactory`, and of a `@HiltWorker`. Hilt builds the factory as an anonymous `SingletonCImpl$SwitchingProvider$N`; the whole `create(...)` is wrapped, so the creation of the dependencies shows nested inside. The allocation of the factory object itself has no section. Observed: `...demo.AssistedWidget` containing `...greeting.GreetingRepository`. |
| `hilt.resolve` | `Provider.suspending` or `Lazy.await` | The suspend adapters of `hilt-coroutines-runtime`, around the blocking `get()`. The `hilt.create` slice of the resolved binding nests inside (`DeferredResolutionTraceTest`). The name does not identify the binding. |
| `hilt.inject` | `<SimpleName>.members` | Member injection of an `@AndroidEntryPoint` class: Activity, View, Service, Fragment (plain, nested child, `DialogFragment`) and BroadcastReceiver shapes. |
| `hilt.inject` | `<Component>.<method>` | An `@EntryPoint` accessor method, named after the component it is installed in. |
| `hilt.inject` | `<SimpleName>.injectMembers` | **Opt-in** ([below](#members-injectors-opt-in)): the call of `injectMembers(T)` on a `MembersInjector<T>` obtained through DI. The suffix keeps it apart from the `.members` sections of `@AndroidEntryPoint` classes. |

`@HiltViewModel` creation is a `hilt.create` section named after the ViewModel class (`ViewModelCImpl$SwitchingProvider` is accepted by the filter). Evidence per kind:

| Kind | Evidence |
|------|----------|
| Activity, View, Service injection; ViewModel, singleton, `Provider`, `Lazy` creation | `TraceCaptureTest` on the real debug build of the sample, run on an Android 15 emulator. |
| `@AssistedInject` creation through its factory | `AssistedCreationTraceTest` on the real debug build of the sample (Android 15 emulator): `hilt.inject SingletonComponent.assistedWidgetFactory`, then `hilt.create ...AssistedWidget` with `hilt.create ...GreetingRepository` inside it, and no section named after `SwitchingProvider$N`. ASM tests on classes captured from real Hilt output (`AssistedFactoryRuleTest`). |
| `@HiltWorker` creation | Not verified end to end: the sample has no WorkManager. It shares the rule with `@AssistedInject` (a `SwitchingProvider$N` whose `create(...)` constructs the class), which is tested on assisted classes only. |
| `MembersInjector.injectMembers` (opt-in) | `MembersInjectorTraceTest` on the real debug build and on the R8-minified build of the sample (Android 15 emulator): `hilt.inject SingletonComponent.membersInjector`, then `hilt.inject MembersInjectionTarget.injectMembers` with `hilt.create ...GreetingRepository` nested inside it. ASM tests on classes captured from real Hilt output (`MembersInjectorRuleTest`, `MembersInjectorWiringTest`). |
| Fragment injection (plain) | `FragmentTraceCaptureTest` on the real debug build (Android 15 emulator): `hilt.inject DemoFragment.members` with `hilt.create ...GreetingRepository` inside. ASM tests on a class captured from real Hilt output (`InjectRuleTest`, `FixtureCoverageTest`). |
| Nested Fragment (child of a Fragment) and `DialogFragment` | `FragmentShapesTraceTest` (debug and minified, Android 15 emulator): one `hilt.inject ShapesParentFragment.members`, one `...ShapesChildFragment.members` and one `...ShapesDialogFragment.members`, each a sibling root slice next to `ShapesFragmentHostActivity.members` (each injects in its own `onAttach`, so the child is not nested inside the parent's slice). The repository is created inside the first one that needs it. |
| `@AndroidEntryPoint` BroadcastReceiver | `BroadcastReceiverTraceTest` (debug and minified, Android 15 emulator): a real `sendBroadcast` to the manifest-registered receiver records `hilt.inject ShapeReceiver.members` with `hilt.create ...GreetingRepository` inside it (`Hilt_ShapeReceiver.onReceive` calls `inject()`, which is `SingletonCImpl.inject`). |
| `@HiltViewModel` from `hiltViewModel()` (Compose), plain and with `SavedStateHandle` | `HiltViewModelTraceTest` (debug and minified, Android 15 emulator, `androidx.hilt:hilt-navigation-compose` 1.4.0): one `hilt.create ...PlainShapeViewModel` and one `hilt.create ...SavedStateShapeViewModel`, plus `hilt.inject ShapesComposeActivity.members` for the host. Navigation-scoped ViewModels (a ViewModel tied to a navigation back stack entry) are **unverified**: the test uses the activity's ViewModel store. |

A plain `Provider.get()` or `Lazy.get()` in your own code has no section that names its caller: its `hilt.create` slice is a root slice unless your code wraps the call in a section ([Coroutines](COROUTINES.md#trace-section-of-the-adapters), [Limitations](LIMITATIONS.md)).

### Members injectors (opt-in)

Off by default. Hilt generates a `Target_MembersInjector` for a class with `@Inject` fields or methods, with a real instance method `injectMembers(Target)` that the component never calls (it calls the static setters). Code that receives a `MembersInjector<Target>` through DI and calls it is the only caller. To trace that call:

```kotlin
hiltTrace {
    traceMembersInjectors.set(true)   // default false
}
```

With the option on, the class filter admits `*_MembersInjector` and the whole `injectMembers(Target)` body is wrapped in a `hilt.inject` section named `<Target simple name>.injectMembers`; the creation of the dependencies it needs nests inside. A class or method of another shape, a method over the code limit or an error leaves the class unchanged with one warning, like the other rules. With the option off the output is unchanged and these classes are not offered to the transform. Release variants stay uninstrumented either way.

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
- A system-wide `perfetto` capture does not contain the `hilt.*` sections by default: they only go through the in-process `tracing-wire` file. The opt-in system trace bridge below mirrors them; it is off by default. Verified once on an Android 15 emulator (API 35) with the sample (see below).

## System-wide Perfetto capture

`HiltTrace.setSystemTraceEnabled(true)` also mirrors each `hilt.create` and `hilt.inject` section to the system trace (`androidx.tracing.Trace.beginSection` / `endSection`; the label is `<category> <name>`, for example `hilt.inject MainActivity.members`, because `Trace.beginSection` has no category field; labels are cut at 127 characters). It is off by default, so nothing changes until you call it. Call it early, for example in `Application.onCreate`; the sample does so for debuggable builds only. The mirror records only while a system trace session runs, and it adds cost on the traced call path. Benchmark cases for that cost exist (see [Benchmarks](BENCHMARK.md)); no figure is recorded yet because a physical-device run is pending.

```bash
PKG=io.github.santimattius.android.sample
curl -O https://raw.githubusercontent.com/google/perfetto/main/tools/record_android_trace && chmod +x record_android_trace
./record_android_trace -t 45s -b 64mb -o hilt.perfetto-trace sched freq gfx am wm view -a $PKG
# start the app and press the Run buttons while the capture runs
```

Open the file in the [Perfetto UI](https://ui.perfetto.dev/).

**What was verified.** The label format (`hilt.create <name>` / `hilt.inject <name>`, cut at 127 characters on the name side so the category prefix is kept) is verified by JVM unit tests (`HiltTraceTest`, with a fake of the platform trace) and by one capture on an Android 15 emulator (API 35): the sample debug build, `record_android_trace -t 90s -b 128mb ... -a <package>`, and every Run button of the sample pressed while it ran. In that capture the app process held `hilt.inject MainActivity.members`, `hilt.inject DemoView.members`, `hilt.inject DemoService.members`, and `hilt.create` slices for `ChainA` to `ChainE`, `SlowOne`, `SlowTwo`, `SlowThree`, `WideFanOut`, `FreshWidget`, `DeferredGraph`, `SingletonCounter`, `ExpensiveService`, `ScratchViewModel` and `LibraryGreeting`, next to scheduling and frame data. This is one run on one image: it shows that the labels and the mirror work, not that every section of every app appears. An earlier capture, made with the previous format (the label was the section name alone), is not repeated here.

**Differences from the in-process file.**
- The system slice name starts with the category, then one space, then the section name: `hilt.inject MainActivity.members`, `hilt.create <qualified type>`. Search for `hilt.` to find every section, or `hilt.inject` / `hilt.create` for one category. When the label would exceed 127 characters, the end of the name is cut; the category prefix is always kept. The in-process file keeps the bare section name, with the category as its own field.
- With the mirror on and a `tracing-wire` driver active, a section is written to both places.

## Release builds and the catalogue

- Release classes stay unchanged. The plugin excludes `META-INF/hilt-trace/**` from variants it does not instrument. With minification off, the unused `HiltTrace` runtime classes still ship in the release dex.
- AGP's default packaging excludes (`**/_*`) already keep underscore-named catalogue files out of APKs, in debug too. The transform reads the catalogue at build time, so this is harmless.
