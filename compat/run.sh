#!/usr/bin/env bash
# Builds the throwaway consumer in compat/consumer against locally published artifacts and checks
# that the debug dex carries the Hilt trace instrumentation and the release dex does not.
#
# Usage: compat/run.sh <agp> <kotlin> <hilt> <ksp> [<consumer-minSdk>]   (minSdk defaults to 24)
# Environment:
#   COMPAT_M2            local Maven repository directory (default: a fresh temporary directory)
#   COMPAT_JAVA_HOME     JDK used by the consumer build (default: the current JDK)
#   COMPAT_SKIP_PUBLISH  set to 1 to reuse artifacts already present in COMPAT_M2
#   COMPAT_EXPECT_MIN_SDK_REJECTION  set to 1 to expect the manifest merger to reject the consumer's
#                        minSdk because the published libraries declare a higher one (minSdk probe);
#                        the run then passes only if that exact rejection happens.
#   COMPAT_API23_PROBE   evidence-only API 23 probe (use with consumer-minSdk 23); the published minSdk stays 24.
#                        "build": the minSdk check is overridden only in the consumer (tools:overrideLibrary,
#                        compat/consumer/src/probe) and the debug app and its smoke test APK are assembled.
#                        "run": the same, then the smoke test runs on the connected device/emulator, which
#                        must be API 23 (expectedSdk=23); the compat.yml job api23-probe provides one.
set -euo pipefail

if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
  echo "usage: $0 <agp> <kotlin> <hilt> <ksp> [<consumer-minSdk>]" >&2
  exit 2
fi
AGP=$1 KOTLIN=$2 HILT=$3 KSP=$4 MIN_SDK=${5:-24}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
M2=${COMPAT_M2:-$(mktemp -d)}
LIBRARY_VERSION=$(sed -n 's/^libraryVersion=//p' "$ROOT/gradle.properties")
GRADLEW="$ROOT/gradlew"

if [ "${COMPAT_SKIP_PUBLISH:-0}" != "1" ]; then
  # Signing is skipped only for this throwaway local repository.
  PUBLISH_ARGS=(publishToMavenLocal -Philtext.skipSigning=true "-Dmaven.repo.local=$M2")
  (cd "$ROOT" && "$GRADLEW" "${PUBLISH_ARGS[@]}")
  (cd "$ROOT" && "$GRADLEW" -p hilt-trace-gradle-plugin "${PUBLISH_ARGS[@]}")
fi

CONSUMER_ARGS=(
  -p "$ROOT/compat/consumer"
  "-Dmaven.repo.local=$M2"
  "-PcompatAgp=$AGP" "-PcompatKotlin=$KOTLIN" "-PcompatHilt=$HILT" "-PcompatKsp=$KSP"
  "-PcompatLibraryVersion=$LIBRARY_VERSION" "-PcompatMinSdk=$MIN_SDK"
)
if [ -n "${COMPAT_JAVA_HOME:-}" ]; then
  CONSUMER_ARGS+=("-Dorg.gradle.java.home=$COMPAT_JAVA_HOME")
fi
if [ "${COMPAT_EXPECT_MIN_SDK_REJECTION:-0}" = "1" ]; then
  LOG=$(mktemp)
  if "$GRADLEW" "${CONSUMER_ARGS[@]}" clean processDebugMainManifest >"$LOG" 2>&1; then
    echo "expected the manifest merger to reject minSdk $MIN_SDK, but the build succeeded:" >&2
    echo "the libraries may now support it; update docs/SETUP.md and docs/LIMITATIONS.md and the matrix." >&2
    exit 1
  fi
  if grep -q "uses-sdk:minSdkVersion $MIN_SDK cannot be smaller than version" "$LOG"; then
    grep -m1 "cannot be smaller than version" "$LOG" | cut -c1-300
    echo "minSdk $MIN_SDK rejected by the manifest merger, as expected"
    exit 0
  fi
  echo "build failed, but not with the expected minSdk rejection (last lines):" >&2
  tail -n 30 "$LOG" >&2
  exit 1
fi

case "${COMPAT_API23_PROBE:-}" in
  build)
    "$GRADLEW" "${CONSUMER_ARGS[@]}" -PcompatOverrideLibrary=true clean assembleDebug assembleDebugAndroidTest
    echo "API 23 probe assembled (minSdk $MIN_SDK, minSdk check overridden in the consumer only); not run on a device"
    exit 0
    ;;
  run)
    "$GRADLEW" "${CONSUMER_ARGS[@]}" -PcompatOverrideLibrary=true \
      -Pandroid.testInstrumentationRunnerArguments.expectedSdk=23 clean connectedDebugAndroidTest
    exit 0
    ;;
  "") ;;
  *) echo "COMPAT_API23_PROBE must be 'build' or 'run'" >&2; exit 2 ;;
esac

"$GRADLEW" "${CONSUMER_ARGS[@]}" clean assembleDebug assembleRelease

"$ROOT/compat/check-dex.sh" "$ROOT/compat/consumer"
