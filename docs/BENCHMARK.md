# Disabled-category microbenchmark

The `:benchmark-micro` module holds a Jetpack Microbenchmark (`androidx.benchmark:benchmark-junit4`) that compares two call paths while a trace category is **disabled**:

- `CallPaths.baseline`: the work only.
- `CallPaths.instrumented`: `HiltTrace.begin`, the same work, `HiltTrace.end` in a `finally`. This is the shape of the code Hilt Trace generates around a creation case.

The difference between the two medians is the cost of the disabled-category check. The module is never published and nothing depends on it.

## What is proved

- The instrumented path returns the same result as the baseline, and `begin` returns `null` for the benchmarked category when no driver is installed (`CallPathsTest`, JVM unit test).
- The benchmark and its APK compile and package (`:benchmark-micro:assembleMicrobenchmark`).

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
- It says nothing about the total effect on an app start. Issue #72 tracks recording this in the docs and measuring a real start.

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

**This repository states no app start figure yet.** The maintainer records one after running the script on a physical device.

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
