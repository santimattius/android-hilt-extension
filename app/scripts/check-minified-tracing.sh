#!/usr/bin/env bash
# Usage: check-minified-tracing.sh <minified-apk> <r8-mapping.txt> <dexdump>
# Proves that the tracing instrumentation survives R8 in the minified APK.
#
# R8 inlines the small HiltTrace facade into the instrumented call sites and removes the class, so the
# check does not look for HiltTrace itself. It asserts what the instrumented code must still carry:
# the category and slice-name string constants that only the plugin's rewrite emits. The slice names are
# exact (for example "MainActivity.members"); the sample's UI strings that mention them are longer.
# The mapping file exists only when R8 ran over the variant.
set -euo pipefail

APK=${1:?usage: check-minified-tracing.sh <apk> <mapping> <dexdump>}
MAPPING=${2:?missing mapping file}
DEXDUMP=${3:?missing dexdump}

[ -f "$APK" ] || { echo "no minified APK at $APK" >&2; exit 2; }
[ -f "$MAPPING" ] || { echo "no R8 mapping at $MAPPING: the variant was not minified" >&2; exit 2; }
[ -x "$DEXDUMP" ] || { echo "dexdump not executable: $DEXDUMP" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -qo "$APK" 'classes*.dex' -d "$WORK"
for dex in "$WORK"/classes*.dex; do "$DEXDUMP" -d "$dex"; done > "$WORK/dump.txt"

missing=0
for constant in \
  hilt.inject hilt.create \
  MainActivity.members FragmentHostActivity.members DemoFragment.members DemoView.members DemoService.members \
  io.github.santimattius.android.sample.android.ScratchViewModel; do
  count=$(grep -F "const-string" "$WORK/dump.txt" | grep -c -F "\"$constant\"" || true)
  echo "  \"$constant\": $count const-string site(s)"
  [ "$count" -gt 0 ] || missing=1
done

if [ "$missing" -ne 0 ]; then
  echo "instrumentation constants are missing from the minified dex: R8 removed tracing call sites" >&2
  exit 1
fi
echo "minified APK keeps the tracing instrumentation (R8 mapping: $(wc -l < "$MAPPING" | tr -d ' ') lines)"
