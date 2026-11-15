#!/usr/bin/env bash
# Fixture tests for the statistics mode of measure-startup.sh (no device needed).
# Run: app/scripts/test-measure-startup.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
tool="$here/measure-startup.sh"
fails=0

check() { # check <name> <expected stdout> <input>
  local name="$1" want="$2" input="$3" got
  got=$(printf '%s\n' "$input" | "$tool" --stats 2>/dev/null) || got="exit $?"
  if [ "$got" = "$want" ]; then echo "ok   $name"; else
    echo "FAIL $name"; echo "  want: $want"; echo "  got:  $got"; fails=$((fails + 1)); fi
}

# p90 is nearest-rank; quartiles use linear interpolation (type 7). Hand computed:
# 1..9 unsorted: median=5, p90=ceil(.9*9)=9th=9, Q1=3, Q3=7, IQR=4
check "odd count" "n=9 median=5 p90=9 min=1 max=9 iqr=4" "$(printf '%s\n' 5 3 1 9 7 2 8 4 6)"
# 10 20 30 40: median=25, p90=ceil(3.6)=4th=40, Q1=17.5, Q3=32.5, IQR=15
check "even count" "n=4 median=25 p90=40 min=10 max=40 iqr=15" "$(printf '%s\n' 10 20 30 40)"
check "single value" "n=1 median=7 p90=7 min=7 max=7 iqr=0" "7"
# 100 100 100 100 130: median=100, p90=5th=130, Q1=Q3=100
check "duplicates" "n=5 median=100 p90=130 min=100 max=130 iqr=0" "$(printf '%s\n' 100 130 100 100 100)"

if printf '' | "$tool" --stats >/dev/null 2>&1; then
  echo "FAIL empty input must fail"; fails=$((fails + 1)); else echo "ok   empty input fails"; fi
if printf 'abc\n' | "$tool" --stats >/dev/null 2>&1; then
  echo "FAIL non-numeric input must fail"; fails=$((fails + 1)); else echo "ok   non-numeric input fails"; fi

if [ "$fails" -eq 0 ]; then echo "all passed"; else echo "$fails failed"; exit 1; fi
