#!/usr/bin/env bash
# Fixture tests for scripts/assert-instrumented-results.sh. Run: scripts/test-assert-instrumented-results.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/assert-instrumented-results.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fails=0

expect() { # expect <exit: zero|nonzero> <name> -- <guard args...>
  local want="$1" name="$2"; shift 3
  "$guard" "$@" >/dev/null 2>&1
  local rc=$?
  if { [ "$want" = zero ] && [ $rc -eq 0 ]; } || { [ "$want" = nonzero ] && [ $rc -ne 0 ]; }; then
    echo "ok   $name (exit $rc)"
  else
    echo "FAIL $name (exit $rc, wanted $want)"; fails=$((fails + 1))
  fi
}

# Real shape of the false green seen on PR #59: one suite, one testRunFailed testcase.
cat > "$tmp/crashed.xml" <<'X'
<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="android-test" tests="1" failures="1" errors="0" skipped="0">
  <testcase name="testRunFailed" classname="android-test" time="0.0">
    <failure>Test run failed to complete. Instrumentation run failed due to Process crashed.</failure>
  </testcase>
</testsuite>
X
cat > "$tmp/pass.xml" <<'X'
<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="ci" tests="2" failures="0" errors="0" skipped="0">
  <testcase name="a" classname="p.A" time="0.1"/>
  <testcase name="b" classname="p.A" time="0.1"/>
</testsuite>
X
cat > "$tmp/failing.xml" <<'X'
<testsuite name="ci" tests="2" failures="1" errors="0" skipped="0">
  <testcase name="a" classname="p.A" time="0.1"/>
  <testcase name="b" classname="p.A" time="0.1"><failure>boom</failure></testcase>
</testsuite>
X
cat > "$tmp/error.xml" <<'X'
<testsuite name="ci" tests="1" failures="0" errors="1" skipped="0">
  <testcase name="a" classname="p.A" time="0.1"><error>boom</error></testcase>
</testsuite>
X
cat > "$tmp/empty-suite.xml" <<'X'
<testsuite name="ci" tests="0" failures="0" errors="0" skipped="0"></testsuite>
X
cat > "$tmp/runfailed-clean.xml" <<'X'
<testsuite name="android-test" tests="2" failures="0" errors="0" skipped="0">
  <testcase name="testRunFailed" classname="android-test"/>
  <testcase name="a" classname="p.A"/>
</testsuite>
X

expect zero    "passing XML"                  -- "$tmp/pass.xml"
expect nonzero "crashed run (PR #59 shape)"   -- "$tmp/crashed.xml"
expect nonzero "failing testcase"             -- "$tmp/failing.xml"
expect nonzero "error testcase"               -- "$tmp/error.xml"
expect nonzero "zero testcases"               -- "$tmp/empty-suite.xml"
expect nonzero "testRunFailed without failure element" -- "$tmp/runfailed-clean.xml"
expect nonzero "glob matching nothing"        -- "$tmp/nothing/*.xml"
expect nonzero "no arguments"                 --
expect zero    "two files, both passing"      -- "$tmp/pass.xml" "$tmp/pass.xml"
expect nonzero "one passing and one crashed"  -- "$tmp/pass.xml" "$tmp/crashed.xml"

[ $fails -eq 0 ] && echo "all fixtures behave" || { echo "$fails fixture(s) failed"; exit 1; }
