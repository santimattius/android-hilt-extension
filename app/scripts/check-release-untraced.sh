#!/usr/bin/env bash
# Usage: check-release-untraced.sh <release-apk> <r8-mapping.txt> <dexdump>
# Proves that a minified release APK carries no tracing instrumentation.
#
# Fails when the dex holds any call site of HiltTrace.begin or any androidx/tracing reference, and when
# the R8 mapping is missing (so the check cannot pass on an unminified build when minification was meant
# to be on). R8 may inline or rename HiltTrace, so the check looks for the call sites and for the
# androidx.tracing library itself, which the sample's release does not otherwise use.
set -euo pipefail

APK=${1:?usage: check-release-untraced.sh <apk> <mapping> <dexdump>}
MAPPING=${2:?missing mapping file}
DEXDUMP=${3:?missing dexdump}

[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 2; }
[ -f "$MAPPING" ] || { echo "no R8 mapping at $MAPPING: the build was not minified" >&2; exit 2; }
[ -x "$DEXDUMP" ] || { echo "dexdump not executable: $DEXDUMP" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -qo "$APK" 'classes*.dex' -d "$WORK"
for dex in "$WORK"/classes*.dex; do "$DEXDUMP" -d "$dex"; done > "$WORK/dump.txt"

begin=$(grep -c -F 'HiltTrace;.begin' "$WORK/dump.txt" || true)
tracing=$(grep -c -F 'androidx/tracing' "$WORK/dump.txt" || true)
echo "  HiltTrace;.begin call sites: $begin"
echo "  androidx/tracing references: $tracing"

if [ "$begin" -ne 0 ] || [ "$tracing" -ne 0 ]; then
  echo "the release dex carries tracing instrumentation" >&2
  exit 1
fi
echo "release APK is uninstrumented (R8 mapping: $(wc -l < "$MAPPING" | tr -d ' ') lines)"
