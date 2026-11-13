#!/usr/bin/env bash
# Usage: compat/check-dex.sh <consumer-dir>
# The debug APK must contain HiltTrace.begin call sites; the release APK must contain none.
set -euo pipefail

DIR=${1:?usage: check-dex.sh <consumer-dir>}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
DEXDUMP=$(ls "$SDK"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)
if [ -z "$DEXDUMP" ]; then
  echo "dexdump not found under ANDROID_HOME/build-tools" >&2
  exit 2
fi

# Prints the number of HiltTrace.begin call sites in the dex files of one variant's APK.
count_calls() {
  local apk work
  apk=$(ls "$DIR/build/outputs/apk/$1/"*.apk 2>/dev/null | head -1 || true)
  if [ -z "$apk" ]; then
    echo "no $1 APK under $DIR" >&2
    exit 2
  fi
  work=$(mktemp -d)
  unzip -qo "$apk" 'classes*.dex' -d "$work"
  for dex in "$work"/classes*.dex; do "$DEXDUMP" -d "$dex"; done | grep -c 'HiltTrace;.begin' || true
}

DEBUG=$(count_calls debug)
RELEASE=$(count_calls release)
echo "HiltTrace.begin call sites: debug=$DEBUG release=$RELEASE"
if [ "$DEBUG" -eq 0 ] || [ "$RELEASE" -ne 0 ]; then
  echo "expected debug > 0 and release == 0" >&2
  exit 1
fi
