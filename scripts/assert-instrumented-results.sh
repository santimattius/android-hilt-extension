#!/usr/bin/env bash
# Fails a CI job when instrumented-test result XMLs do not prove a real run. AGP can finish the Gradle
# task successfully while the instrumentation process crashed at startup, leaving one `testRunFailed`
# testcase (or no tests at all); this guard turns that false green into a failure.
#
# Usage: scripts/assert-instrumented-results.sh '<xml-or-glob>' ['<xml-or-glob>' ...]
# Quote the globs so they expand here. Fails when:
#   - no XML file matches,
#   - the total number of testcases is 0,
#   - any testcase has a <failure> or <error> element,
#   - any testcase is named testRunFailed.
set -u

if [ $# -eq 0 ]; then
  echo "usage: $0 '<result-xml-glob>' [...]" >&2
  exit 2
fi

files=()
for pattern in "$@"; do
  # Intentionally unquoted: expand the glob; a pattern that matches nothing stays literal and is skipped.
  for f in $pattern; do
    [ -f "$f" ] && files+=("$f")
  done
done

if [ ${#files[@]} -eq 0 ]; then
  echo "FAIL: no result XML matched: $*" >&2
  exit 1
fi

total=0
bad=0
for f in "${files[@]}"; do
  cases=$(grep -c '<testcase' "$f" || true)
  failures=$(grep -c -E '<(failure|error)([ >/]|$)' "$f" || true)
  runfailed=$(grep -c -E '<testcase[^>]*name="testRunFailed"' "$f" || true)
  echo "$f: testcases=$cases failures_or_errors=$failures testRunFailed=$runfailed"
  total=$((total + cases))
  if [ "$failures" -gt 0 ] || [ "$runfailed" -gt 0 ]; then
    bad=1
  fi
done

if [ "$total" -eq 0 ]; then
  echo "FAIL: ${#files[@]} result file(s) found but 0 testcases ran" >&2
  exit 1
fi
if [ "$bad" -ne 0 ]; then
  echo "FAIL: failed/errored testcases or testRunFailed (instrumentation crashed) in the results above" >&2
  exit 1
fi
echo "OK: $total testcases in ${#files[@]} file(s), no failures"
