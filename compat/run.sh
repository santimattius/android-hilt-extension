#!/usr/bin/env bash
# Builds the throwaway consumer in compat/consumer against locally published artifacts and checks
# that the debug dex carries the Hilt trace instrumentation and the release dex does not.
#
# Usage: compat/run.sh <agp> <kotlin> <hilt> <ksp>
# Environment:
#   COMPAT_M2            local Maven repository directory (default: a fresh temporary directory)
#   COMPAT_JAVA_HOME     JDK used by the consumer build (default: the current JDK)
#   COMPAT_SKIP_PUBLISH  set to 1 to reuse artifacts already present in COMPAT_M2
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <agp> <kotlin> <hilt> <ksp>" >&2
  exit 2
fi
AGP=$1 KOTLIN=$2 HILT=$3 KSP=$4

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
  "-PcompatLibraryVersion=$LIBRARY_VERSION"
)
if [ -n "${COMPAT_JAVA_HOME:-}" ]; then
  CONSUMER_ARGS+=("-Dorg.gradle.java.home=$COMPAT_JAVA_HOME")
fi
"$GRADLEW" "${CONSUMER_ARGS[@]}" clean assembleDebug assembleRelease

"$ROOT/compat/check-dex.sh" "$ROOT/compat/consumer"
