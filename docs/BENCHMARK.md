# Disabled-category microbenchmark

The `:benchmark-micro` module holds a Jetpack Microbenchmark (`androidx.benchmark:benchmark-junit4`) that compares two call paths while a trace category is **disabled**:

- `CallPaths.baseline`: the work only.
- `CallPaths.instrumented`: `HiltTrace.begin`, the same work, `HiltTrace.end` in a `finally`. This is the shape of the code Hilt Trace generates around a creation case.

The difference between the two medians is the cost of the disabled-category check. The module is never published and nothing depends on it.

## What is proved

- The instrumented path returns the same result as the baseline, and `begin` returns `null` for the benchmarked category when no driver is installed (`CallPathsTest`, JVM unit test).
- The benchmark and its APK compile and package (`:benchmark-micro:assembleMicrobenchmark`).

## System trace bridge cases

`SystemTraceBridgeBenchmark` (same module, same `CallPaths.instrumented` shape) covers the paths the opt-in system trace bridge adds, with the in-process category disabled:

- `bridgeOff`: the default; one extra volatile read in `begin`.
- `bridgeOnWithoutSession`: the bridge is on and `Trace.isEnabled()` is false, so each call pays the platform check and builds no label.

Not covered: the bridge on with a system trace session running (a platform call per section); it needs a Perfetto session around the run, which the module does not start. Both cases assert that no system trace session is running.

**Status: no figure is recorded.** The cases compile (`:benchmark-micro:assembleMicrobenchmark`) and ran once on an Android 15 emulator, which only shows they execute. Emulator timings are not a measurement of overhead and are not quoted here. A physical-device run is pending; compare its medians with `baseline` and `instrumentedWithDisabledCategory` from the same run.

## What is not proved

## Measured (by hand, 2026-11-15, not CI)

One physical moto g05, Android 15, `speed`-compiled, charging, 29 C, CPU locked; five full runs of the two benchmarks, 50 samples each:

| Path | Median per call |
|---|---|
| `baseline` (the work only) | 4.63 ns (spread of 0.04 ns across runs) |
| `instrumentedWithDisabledCategory` | 24.3 to 25.0 ns in three runs; 33.3 and 35.3 ns in two noisier runs |
| Difference | about 20 ns in the three clean runs, up to about 31 ns in the noisy ones |

The two noisy runs had maxima of 574 and 756 ns and about 0.07 allocations per iteration, which were not attributed; the three clean runs allocated nothing.

How it was run: an emulator was connected as well, so the APK from `:benchmark-micro:assembleMicrobenchmark` was installed with `adb -s <serial> install -t`, compiled with `adb -s <serial> shell cmd package compile -m speed -f <test package>` (the runner raises `NOT-AOT-COMPILED` otherwise) and run with `adb -s <serial> shell am instrument -w <test package>/androidx.benchmark.junit4.AndroidBenchmarkRunner`.

## What is not proved

- It is a synthetic call path with the shape of the generated code and a few instructions of work, not the generated Dagger code, and no tracing driver is installed.
- One device, one manual session; nothing from CI (the emulator smoke run is not a measurement).
- It says nothing by itself about the total effect on an app start; that is measured separately in the "App start" section below.

## Run

| Goal | Command |
|------|---------|
| Compile and package, no device | `./gradlew :benchmark-micro:assembleMicrobenchmark` |
| Run the JVM unit test | `./gradlew :benchmark-micro:testReleaseUnitTest` |
| Run on a connected device | `./gradlew :benchmark-micro:runMicrobenchmark` |

On an emulator the runner refuses to measure; for a smoke run only, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,NOT-AOT-COMPILED` (the emulator image is not `speed`-compiled, so the runner raises `NOT-AOT-COMPILED` as well). Do not quote emulator numbers.

## CI smoke run

The `benchmark-smoke` CI job runs `./gradlew microbenchmarkSmokeCi -PbenchmarkSmoke=true` on a Gradle Managed Device (`smoke`: Pixel 6, API 34, `aosp-atd`, same image as the instrumented jobs). The property sets the runner argument `androidx.benchmark.suppressErrors=EMULATOR,NOT-AOT-COMPILED` for that run only (the first CI run suppressed only `EMULATOR` and failed with `NOT-AOT-COMPILED`); without it the runner keeps refusing emulators. The report and any benchmark JSON are uploaded as the `benchmark-smoke-output` artifact.

**The CI run only proves that the benchmark executes end to end. Emulator timings are not a measurement of overhead**; the job reads no number, asserts no threshold, and this repository still states no overhead figure. Status: the job passed in CI on PR #60 (device `smoke`, API 34): 2 tests, 0 failures, 0 skipped, and the JSON holds 50 runs for each of the two benchmarks. AndroidX prefixes the benchmark names with `EMULATOR_NOT-AOT-COMPILED_` to mark the suppressed errors. The first CI run failed on `NOT-AOT-COMPILED` alone, which is why that error is suppressed too. Not run locally (no emulator) and not run on a physical device; the repository records no figure from these runs.

The benchmark runs against the release build type of the module (`testBuildType = "release"`) so it is not debuggable.

## App start (instrumented vs uninstrumented sample)

`app/scripts/measure-startup.sh` measures the cold start (`am start -W`, `TotalTime`) of the sample on one device with and without the Hilt Trace instrumentation. Both builds are the `minified` build type (R8, non-debuggable, debug keystore), the same code, differing only in instrumentation: `-PhiltTraceEnabled=false` turns the plugin off (default is on). The `minified` type is used because it is the only non-debuggable build that carries the instrumentation (`hiltTrace { variants }`).

### Measured (by hand, 2026-11-15, not CI)

One physical moto g05, Android 15, charging, both APKs `speed`-compiled, cold starts with `am start -W`, 6 blocks of 5 starts per variant with the first of each block discarded (24 measured per variant), variants interleaved:

| Build | n | Median `TotalTime` | p90 | Min | Max | IQR |
|---|---|---|---|---|---|---|
| `minified`, instrumented | 24 | 407.5 ms | 416 ms | 384 ms | 423 ms | 12 ms |
| `minified`, `-PhiltTraceEnabled=false` | 24 | 405.5 ms | 415 ms | 387 ms | 450 ms | 13.25 ms |

The medians differ by 2 ms (the instrumented build is the slower one) while the IQR of each variant is about 12 to 13 ms, so the difference is **within the noise of this run: no effect on app start can be claimed or ruled out at this resolution.** For scale, the per-call cost measured above is about 20 ns, so even a few hundred traced creations would add microseconds. The instrumentation does add code: the `classes.dex` of the instrumented build is 3,800 bytes larger (1,757,796 against 1,753,996 bytes; the APK file is the same size, 1,550,792 bytes, in both builds).

**Limits:** one device and one session; a 2 ms difference cannot be told from noise with 24 starts; it measures `am start -W` to the first activity, not the time to the first rendered frame or to a fully populated screen; the sample creates few bindings at start; no tracing driver is recording, which is the case this measurement is about.

Build both APKs (they share one output path, so copy the first before the second build):

```sh
./gradlew :app:assembleMinified
cp app/build/outputs/apk/minified/app-minified.apk /tmp/instrumented.apk
./gradlew -PhiltTraceEnabled=false :app:assembleMinified
cp app/build/outputs/apk/minified/app-minified.apk /tmp/plain.apk
```

Run (always pass the serial; `adb devices` lists it):

```sh
app/scripts/measure-startup.sh <adb-serial> /tmp/instrumented.apk instrumented /tmp/plain.apk plain [runs-per-block=5] [blocks=6]
```

The script installs each APK (`adb install -r`) and `speed`-compiles it before every block, then force-stops and starts the app with a 2 s pause. Variants alternate in blocks (A, B, A, B, ...) so thermal drift and background load hit both; the first start of each block is discarded as warm-up. Keep the device charging, screen on and idle, and run it a few times.

How to read it: per variant it prints `n`, `median`, `p90`, `min`, `max` and `iqr` of `TotalTime` in ms, then the difference of the medians. The last line says whether that difference is smaller than the larger IQR; if so it is within noise and no effect can be claimed. `app/scripts/test-measure-startup.sh` tests the statistics (`measure-startup.sh --stats`) without a device. Emulator numbers mean nothing.
