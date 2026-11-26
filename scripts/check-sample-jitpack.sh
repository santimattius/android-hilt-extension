#!/usr/bin/env bash
# Device-free consumer smoke against hosted JitPack artifacts.
set -euo pipefail

version="${1:-v1.0.0-alpha04}"
if (( $# )); then shift; fi
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./gradlew "-PsampleJitPackVersion=$version" :app:verifySampleJitPack \
  :sample-feature:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease "$@"
