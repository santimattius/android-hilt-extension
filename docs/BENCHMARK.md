# Disabled-category microbenchmark

The `:benchmark-micro` module holds a Jetpack Microbenchmark (`androidx.benchmark:benchmark-junit4`) that compares two call paths while a trace category is **disabled**:

- `CallPaths.baseline`: the work only.
- `CallPaths.instrumented`: `HiltTrace.begin`, the same work, `HiltTrace.end` in a `finally`. This is the shape of the code Hilt Trace generates around a creation case.

The difference between the two medians is the cost of the disabled-category check. The module is never published and nothing depends on it.

## What is proved

- The instrumented path returns the same result as the baseline, and `begin` returns `null` for the benchmarked category when no driver is installed (`CallPathsTest`, JVM unit test).
- The benchmark and its APK compile and package (`:benchmark-micro:assembleMicrobenchmark`).

## What is not proved

No timing has been recorded or claimed. Microbenchmark numbers need a device (preferably physical, not an emulator), so this repository states no overhead figure. Run it on a device and read the two medians.

## Run

| Goal | Command |
|------|---------|
| Compile and package, no device | `./gradlew :benchmark-micro:assembleMicrobenchmark` |
| Run the JVM unit test | `./gradlew :benchmark-micro:testReleaseUnitTest` |
| Run on a connected device | `./gradlew :benchmark-micro:runMicrobenchmark` |

On an emulator the runner refuses to measure; for a smoke run only, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,NOT-AOT-COMPILED` (the emulator image is not `speed`-compiled, so the runner raises `NOT-AOT-COMPILED` as well). Do not quote emulator numbers.

## CI smoke run

The `benchmark-smoke` CI job runs `./gradlew microbenchmarkSmokeCi -PbenchmarkSmoke=true` on a Gradle Managed Device (`smoke`: Pixel 6, API 34, `aosp-atd`, same image as the instrumented jobs). The property sets the runner argument `androidx.benchmark.suppressErrors=EMULATOR,NOT-AOT-COMPILED` for that run only (the first CI run suppressed only `EMULATOR` and failed with `NOT-AOT-COMPILED`); without it the runner keeps refusing emulators. The report and any benchmark JSON are uploaded as the `benchmark-smoke-output` artifact.

**The CI run only proves that the benchmark executes end to end. Emulator timings are not a measurement of overhead**; the job reads no number, asserts no threshold, and this repository still states no overhead figure. Status: configuration checked locally with `--dry-run`; the on-device run is **pending CI** (no emulator locally).

The benchmark runs against the release build type of the module (`testBuildType = "release"`) so it is not debuggable.
