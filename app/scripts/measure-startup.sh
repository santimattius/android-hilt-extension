#!/usr/bin/env bash
# Usage:
#   measure-startup.sh <adb-serial> <apk-A> <label-A> <apk-B> <label-B> [runs-per-block=5] [blocks=6]
#   measure-startup.sh --stats        (one number per line on stdin; no device needed)
#
# Compares cold app start (am start -W, TotalTime) of two builds of the sample on ONE device. Always uses
# `adb -s <serial>`; it never touches the default device. Both APKs share the package, so only one can be
# installed at a time: the variants are interleaved in blocks (A, B, A, B, ...) and each block re-installs
# its APK (adb install -r) and AOT-compiles it (cmd package compile -m speed -f), so drift in temperature
# and background load hits both variants. The first start of every block is discarded as warm-up.
# See docs/BENCHMARK.md ("App start").
set -euo pipefail

PACKAGE=${PACKAGE:-io.github.santimattius.android.sample}
ACTIVITY=${ACTIVITY:-.MainActivity}
PAUSE=${PAUSE:-2}

# stats: one number per line on stdin -> "n= median= p90= min= max= iqr=".
# p90 is nearest-rank; quartiles use linear interpolation (type 7).
stats() {
  sort -n | awk '
    function q(p,   pos, lo) { pos = (n - 1) * p + 1; lo = int(pos); if (lo >= n) return v[n]; return v[lo] + (pos - lo) * (v[lo + 1] - v[lo]) }
    !/^[0-9]+([.][0-9]+)?$/ { bad = 1 }
    { v[++n] = $1 }
    END {
      if (bad || n == 0) { print "stats: need one non-negative number per line" > "/dev/stderr"; exit 1 }
      r = 0.9 * n; k = int(r); if (k < r) k++
      printf "n=%d median=%.10g p90=%.10g min=%.10g max=%.10g iqr=%.10g\n", n, q(0.5), v[k], v[1], v[n], q(0.75) - q(0.25)
    }'
}

if [ "${1:-}" = "--stats" ]; then stats; exit; fi

SERIAL=${1:?usage: measure-startup.sh <adb-serial> <apk-A> <label-A> <apk-B> <label-B> [runs-per-block=5] [blocks=6]}
APK_A=${2:?missing apk-A}; LABEL_A=${3:?missing label-A}
APK_B=${4:?missing apk-B}; LABEL_B=${5:?missing label-B}
RUNS=${6:-5}; BLOCKS=${7:-6}
[ -f "$APK_A" ] || { echo "no APK at $APK_A" >&2; exit 2; }
[ -f "$APK_B" ] || { echo "no APK at $APK_B" >&2; exit 2; }
[ "$RUNS" -ge 2 ] || { echo "runs-per-block must be at least 2 (the first start is warm-up)" >&2; exit 2; }

adbd() { adb -s "$SERIAL" "$@"; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
: > "$WORK/A.txt"; : > "$WORK/B.txt"

run_block() { # run_block <A|B> <apk> <label> <block-no>
  local key=$1 apk=$2 label=$3 no=$4 i out total wait
  adbd install -r "$apk" > /dev/null
  adbd shell cmd package compile -m speed -f "$PACKAGE" > /dev/null
  for i in $(seq 1 "$RUNS"); do
    adbd shell am force-stop "$PACKAGE"
    sleep "$PAUSE"
    out=$(adbd shell am start -W -n "$PACKAGE/$ACTIVITY" | tr -d '\r')
    total=$(printf '%s\n' "$out" | sed -n 's/^TotalTime: *//p')
    wait=$(printf '%s\n' "$out" | sed -n 's/^WaitTime: *//p')
    [ -n "$total" ] || { echo "no TotalTime in am start output:" >&2; printf '%s\n' "$out" >&2; exit 1; }
    if [ "$i" -eq 1 ]; then
      echo "block $no $label start $i: TotalTime=$total WaitTime=${wait:-?} (warm-up, discarded)"
    else
      echo "block $no $label start $i: TotalTime=$total WaitTime=${wait:-?}"
      echo "$total" >> "$WORK/$key.txt"
    fi
  done
}

for b in $(seq 1 "$BLOCKS"); do
  run_block A "$APK_A" "$LABEL_A" "$b"
  run_block B "$APK_B" "$LABEL_B" "$b"
done
adbd shell am force-stop "$PACKAGE"

echo
echo "TotalTime in ms, cold start, device $SERIAL, $BLOCKS blocks x $((RUNS - 1)) measured starts per variant:"
sa=$(stats < "$WORK/A.txt"); sb=$(stats < "$WORK/B.txt")
echo "  $LABEL_A: $sa"
echo "  $LABEL_B: $sb"

field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }
ma=$(field "$sa" median); mb=$(field "$sb" median)
ia=$(field "$sa" iqr); ib=$(field "$sb" iqr)
na=$(field "$sa" n)
if [ "$na" -lt 8 ]; then echo "only $na measured starts per variant: too few to judge noise, use the defaults (5 runs x 6 blocks)."; fi
awk -v la="$LABEL_A" -v lb="$LABEL_B" -v ma="$ma" -v mb="$mb" -v ia="$ia" -v ib="$ib" 'BEGIN {
  d = mb - ma; ad = d < 0 ? -d : d; iq = ia > ib ? ia : ib
  printf "difference of medians (%s - %s): %.10g ms\n", lb, la, d
  if (ad < iq) printf "|difference| %.10g ms is smaller than the larger IQR (%.10g ms): within noise, no effect can be claimed.\n", ad, iq
  else printf "|difference| %.10g ms is not smaller than the larger IQR (%.10g ms): outside the noise of this run, still one device and one session.\n", ad, iq
}'
