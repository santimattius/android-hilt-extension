#!/usr/bin/env bash
# Publish alpha tags for the same four artifacts as Maven Central.
set -euo pipefail

if [[ -z "${VERSION:-}" ]]; then
  printf '%s\n' 'VERSION must be a non-empty JitPack tag or commit.' >&2
  exit 2
fi

if [[ ! "$VERSION" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+-alpha([0-9]+|\.[0-9]+)?$ ]]; then
  printf '%s\n' 'JitPack only publishes alpha version tags (for example v1.2.3-alpha01).' >&2
  exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# JitPack's GROUP is not necessarily its multi-module consumption group.
# Use the final group in both POMs and the plugin's embedded dependency coordinates.
common=(
  '-PlibraryGroupId=com.github.santimattius.android-hilt-extension'
  "-PlibraryVersion=$VERSION"
  '-Philtext.skipSigning=true'
  "$@"
)

./gradlew \
  :hilt-trace-runtime:publishToMavenLocal \
  :hilt-coroutines-runtime:publishToMavenLocal \
  :hilt-trace-compiler:publishToMavenLocal \
  "${common[@]}"

# The aggregate task includes the plugin marker, not only the implementation JAR.
./gradlew -p hilt-trace-gradle-plugin publishToMavenLocal "${common[@]}"
