# Disabled-category microbenchmark

The `:benchmark-micro` module holds a Jetpack Microbenchmark (`androidx.benchmark:benchmark-junit4`) that compares two call paths while a trace category is **disabled**:

- `CallPaths.baseline`: the work only.
- `CallPaths.instrumented`: `HiltTrace.begin`, the same work, `HiltTrace.end` in a `finally`. This is the shape of the code Hilt Trace generates around a creation case.

The difference between the two medians is the cost of the disabled-category check. The module is never published and nothing depends on it.

## What is proved

- The instrumented path returns the same result as the baseline, and `begin` returns `null` for the benchmarked category when no driver is installed (`CallPathsTest`, JVM unit test).
- The benchmark and its APK compile and package (`:benchmark-micro:assembleMicrobenchmark`).

## What is not proved

No timing has been recorded. Microbenchmark numbers need a device (preferably physical, not an emulator), so this repository states no overhead figure. Run it on a device and read the two medians.

## Run

| Goal | Command |
|------|---------|
| Compile and package, no device | `./gradlew :benchmark-micro:assembleMicrobenchmark` |
| Run the JVM unit test | `./gradlew :benchmark-micro:testReleaseUnitTest` |
| Run on a connected device | `./gradlew :benchmark-micro:runMicrobenchmark` |

On an emulator the runner refuses to measure; for a smoke run only, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`. Do not quote emulator numbers. In CI, keep to the assemble task; the device task needs hardware.

The benchmark runs against the release build type of the module (`testBuildType = "release"`) so it is not debuggable.
