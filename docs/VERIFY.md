# Verify it works

All commands run from the repository root. `./gradlew` uses the wrapper (Gradle 9.6.0); the build runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`).

## 1. Unit tests (JVM, no device)

| Scope | Command |
|-------|---------|
| Sample app (demo logic, ViewModel) | `./gradlew :app:testDebugUnitTest` |
| Runtime facade | `./gradlew :hilt-trace-runtime:testDebugUnitTest` |
| Coroutine helpers | `./gradlew :hilt-coroutines-runtime:testDebugUnitTest` |
| Catalogue processor | `./gradlew :hilt-trace-compiler:test` |
| Gradle plugin (ASM rules and TestKit builds, included build) | `./gradlew -p hilt-trace-gradle-plugin check` |
| Convention plugins (included build) | `./gradlew -p build-logic check` |
| Everything, plus version alignment | `./gradlew check` |

Reports: `<module>/build/reports/tests/`.

## 2. Instrumented tests (device or emulator)

```bash
adb devices                                   # one device or emulator in state "device"
./gradlew :app:connectedDebugAndroidTest
./gradlew :hilt-coroutines-runtime:connectedDebugAndroidTest
```

What the app suite covers:

| Test class | Covers |
|------------|--------|
| `TraceCaptureTest` | Installs a recording tracer and checks the real debug build: `hilt.create`/`hilt.inject` nesting, categories off and on independently, plus the section of every sample action (ViewModel, View, Service, singleton once, `Provider` per get, `Lazy`, `traceCoroutine` on and off). |
| `MainActivityDemoTest` | Drives the real activity and asserts every stable result clause on each card: activity recreation and its greeting, the injected view's own text, all dispatcher qualifiers, provider/lazy outcomes, and cancellation behavior. |
| `DemoScreenTest` | The stateless screen: empty state, click callback, result, running state. |

The Gradle exit status can be 0 even when the instrumentation run failed; read the XML in `app/build/outputs/androidTest-results/connected/` (`tests` above 0, `failures` and `errors` 0).

CI only compiles the instrumented tests (`:app:assembleDebugAndroidTest`, `:hilt-coroutines-runtime:assembleDebugAndroidTest`).

## 3. The sample app

```bash
./gradlew :app:installDebug
adb shell am start -n com.santimattius.android.sample/.MainActivity
```

The screen is a list of cards. Each has a title, the promise, the trace section a recording shows for it, a **Run** button and a result text. Logic lives in small injectable classes under `app/src/main/kotlin/.../demo`; `DemoViewModel` holds the state and `DemoScreen` only renders it.

| Card | Run does | Result to expect | Trace section |
|------|----------|------------------|---------------|
| Activity injection | Recreates `MainActivity` | New instance id, `injected with GreetingRepository (Perfetto)` | `hilt.inject` `MainActivity.members` |
| ViewModel creation | Creates a new `ScratchViewModel` | `ScratchViewModel #<id> created with GreetingRepository (Perfetto)` | `hilt.create` `com.santimattius.android.sample.android.ScratchViewModel` |
| View injection | Inflates a new `DemoView` below the card | `Inflated DemoView run #n`; the view shows its own injected text | `hilt.inject` `DemoView.members` |
| Service injection | Starts `DemoService`, which reports back and stops itself | `DemoService #<id> injected with GreetingRepository (Perfetto)` | `hilt.inject` `DemoService.members` |
| Singleton binding | Requests a `@Singleton` twice | `same instance: true` | `hilt.create` `...demo.SingletonCounter`, first run only |
| Qualified dispatchers and scope | Runs a block on each qualified dispatcher and on the scope | Thread name per qualifier; `@ApplicationScope ... (active: true)` | none |
| SuspendProvider | `Provider.suspending(io)` on a scoped and an unscoped binding | scoped same instance `true`, unscoped new instance `true`, thread of `get()` | `hilt.create` `...demo.FreshWidget`, once per `get()` |
| SuspendLazy | Five concurrent callers | `got the same value; the initializer ran 1 time` | none |
| Lazy.await | Resolves a slow `dagger.Lazy` on the default dispatcher | Construction thread is not the main thread; second `await()` same instance | `hilt.create` `...demo.ExpensiveService` |
| traceCoroutine | A block that suspends and switches to the I/O dispatcher | `traceCoroutine returned: refreshed on <io thread>`; `recorded: true/false` shows whether the category is enabled | `sample.coroutine` `refresh` (several segments) |
| Blocking resolution | Cancels a caller while a blocking `get()` runs | `caller cancelled ...: true`, `the running get() still finished: true` | none |
| Complex graph cost | Resolves a simulated-slow graph four ways and times each with a monotonic clock: 5-level chain, 3-sibling fan-out, the same graph behind `Lazy`, a slow `@Singleton` | Elapsed ms per scenario: chain and fan-out equal the sum of their delays, `Lazy` resolves in about 0 ms and moves the cost to first use, singleton get #2 is about 0 ms (delays are simulated; read the shape) | `hilt.create` `...demo.ChainA` with `ChainB` to `ChainE` nested inside; `WideFanOut` with `SlowOne`, `SlowTwo`, `SlowThree` as siblings; `DeferredGraph` with no children; `SlowSingleton` once |
| Complex graph on the main thread | Resolves the same simulated chain and fan-out twice while sampling `Choreographer` frame gaps: a blocking `get()` on the main thread, then `Provider.suspending` on a worker. A spinner in the card freezes during the first | Longest gap between frames is close to the simulated work (about 270 ms) with 1 janky frame when blocking, and about one frame (11 ms at 90 Hz) with 0 when off the main thread; gaps are measured on the device, the work is simulated | `hilt.create` `...demo.ChainA` (with `ChainB` to `ChainE`) and `WideFanOut` twice: on the main thread track, then on a worker track |

There is no tracing on/off toggle: the library has none (see [Tracing](TRACING.md)). Compare a capture taken without `START` (no sections) to one taken with it.

The sample has no Fragment card, so Fragment injection is not exercised here (see [Limitations](LIMITATIONS.md)).

## 4. Capture a trace

Follow [Tracing: Capture a trace](TRACING.md#capture-a-trace), tap the Run buttons, then flush and pull the file. In the [Perfetto UI](https://ui.perfetto.dev/) search for the section names in the table above.

Run on an Android 15 emulator with the sample, one pass over all cards produced these names (and not others from the sample): `MainActivity.members`, `DemoView.members`, `DemoService.members`, `...ScratchViewModel`, `...DemoViewModel`, `...SingletonCounter`, `...FreshWidget`, `sample.coroutine`/`refresh`, and a `...CoroutinesModule_DefaultDispatcherFactory` section (fallback name, because `:hilt-coroutines-runtime` has no catalogue).

## 5. Verify the release build is untouched

These ran against the sample after `./gradlew :app:assembleDebug :app:assembleRelease`. The class directories hold the output of the plugin's transform task.

```bash
CLASSES=app/build/intermediates/classes
PKG=dirs/com/santimattius/android/sample
SP='DaggerSampleApplication_HiltComponents_SingletonC$SingletonCImpl$SwitchingProvider.class'

# Debug: wrapped cases call the facade (one begin call per wrapped case)
javap -c -p "$CLASSES/debug/transformDebugClassesWithAsm/$PKG/$SP" | grep -c 'HiltTrace.begin'

# Release: no call site (prints 0)
javap -c -p "$CLASSES/release/transformReleaseClassesWithAsm/$PKG/$SP" | grep -c 'HiltTrace'

# Release APK: no catalogue entry (prints 0)
unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | grep -c 'hilt-trace'
```

## 6. Troubleshooting

| Symptom | Likely cause | Check or fix |
|---------|--------------|--------------|
| Trace file has no `hilt.*` sections | No driver enabled the categories | Send the `START` broadcast, then `force-stop` and relaunch; flush before pulling. Without `START` the automatic driver recorded nothing. |
| File is 0 bytes or only a header | Not flushed | Send `FLUSH_TRACES_GET_PATH`; use the newest file. |
| `hilt.inject` appears but a binding has no `hilt.create` | Unscoped binding inlined by Dagger | Scope it (`@Singleton`) or request it through `Provider`/`Lazy`. See [Limitations](LIMITATIONS.md). |
| No sections at all in a release build | Release is not instrumented | Use `debug` or `benchmark`, or add the variant to `hiltTrace { variants }` (not verified for other variants). |
| Plugin seems not applied | Wrong module or variant | The plugin must be on the application module. Run `./gradlew :app:tasks --all \| grep -i transform` and look for `transformDebugClassesWithAsm`. |
| Slice names are class names without qualifiers, or a library's bindings are missing | Catalogue empty or absent | Check `<module>/build/generated/ksp/debug/resources/META-INF/hilt-trace/*.json` exists and lists bindings; add `ksp(hilt-trace-compiler)` and `hiltTrace.module` to the library. |
| Warning `hiltTrace.module is not set` | Missing KSP argument | `ksp { arg("hiltTrace.module", ":app") }`. |
| Warning about `dagger.hilt.fastInit=false` | Hilt generates no `SwitchingProvider` | Remove the property; only accessor and injection sections remain otherwise. |
| `HiltTrace` classes still in release dex | Minification is off | Expected; see [Limitations](LIMITATIONS.md). |
| Build warning `... left unchanged` | Unknown class shape (other Hilt version) | Fail-open by design; check the Hilt version against [Setup](SETUP.md). |
| `:generateDebugComposePreviewRunfiles` fails | AGP 9.5.0 with the Compose plugin | Apply the two settings in `app/build.gradle.kts`. |
| Instrumented run "passes" but tests are 0 | Gradle exit status 0 with a failed run | Read the XML results (section 2). |
