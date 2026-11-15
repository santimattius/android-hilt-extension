#!/usr/bin/env bash
# Fixture tests for scripts/check-supported-versions.sh. Run: scripts/test-check-supported-versions.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/check-supported-versions.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fails=0

expect() { # expect <zero|nonzero> <name> <docs> <matrix> [<output-substring>]
  local want="$1" name="$2" docs="$3" matrix="$4" msg="${5:-}"
  local out rc
  out="$("$check" "$docs" "$matrix" 2>&1)"; rc=$?
  if { [ "$want" = zero ] && [ $rc -ne 0 ]; } || { [ "$want" = nonzero ] && [ $rc -eq 0 ]; }; then
    echo "FAIL $name (exit $rc, wanted $want)"; fails=$((fails + 1)); return
  fi
  if [ -n "$msg" ] && ! printf '%s' "$out" | grep -qF -- "$msg"; then
    echo "FAIL $name (output lacks: $msg)"; fails=$((fails + 1)); return
  fi
  echo "ok   $name (exit $rc)"
}

# One validated docs row per Hilt version, plus rows the check must ignore.
docs() {
  local f="$1"; shift
  { echo "## Supported toolchains"; echo; echo "| Consumer toolchain | Status |"; echo "|---|---|"
    for h in "$@"; do echo "| AGP 9.1.1, Kotlin 2.2.21, KSP 2.3.13, Hilt $h | Validated. |"; done
    echo "| AGP 9.5.0, Kotlin 2.4.21, Hilt 2.60.1 | This repository's own build. |"
    echo "| minSdk 23 consumer (AGP 9.1.1, Kotlin 2.2.21, Hilt 2.60.1) | Rejected. |"
    echo; echo "## Application module"
    echo "| AGP 1.0.0, Kotlin 1.0.0, KSP 1.0.0, Hilt 1.0.0 | outside the section, ignored |"; } > "$f"
}
# One matrix entry per Hilt version, plus the minSdk probe (which the check must ignore).
matrix() {
  local f="$1"; shift
  { echo "      matrix:"; echo "        include:"
    for h in "$@"; do echo "          - { agp: \"9.1.1\", kotlin: \"2.2.21\", hilt: \"$h\", ksp: \"2.3.13\" }"; done
    echo "          - { agp: \"9.1.1\", kotlin: \"2.2.21\", hilt: \"2.60.1\", ksp: \"2.3.13\", minsdk: \"23\", expect_rejection: \"1\" }"; } > "$f"
}

docs "$tmp/d-ok.md" 2.59.2 2.60.1;  matrix "$tmp/m-ok.yml" 2.60.1 2.59.2
docs "$tmp/d-less.md" 2.59.2;       docs "$tmp/d-more.md" 2.59.2 2.60.1 2.61.0
matrix "$tmp/m-more.yml" 2.59.2 2.60.1 2.61.0
printf '# nothing here\n' > "$tmp/d-empty.md"
printf 'jobs: {}\n' > "$tmp/m-empty.yml"
printf '%s\n' '          - { agp: "9.1.1", kotlin: "2.2.21", hilt: "2.60.1" }' > "$tmp/m-broken.yml"

expect zero    "docs and matrix match (order and minSdk probe ignored)" "$tmp/d-ok.md" "$tmp/m-ok.yml"
expect nonzero "Hilt only in the matrix" "$tmp/d-less.md" "$tmp/m-ok.yml" "only in compat.yml: AGP 9.1.1, Kotlin 2.2.21, KSP 2.3.13, Hilt 2.60.1"
expect nonzero "Hilt only in the docs" "$tmp/d-more.md" "$tmp/m-ok.yml" "only in docs/SETUP.md: AGP 9.1.1, Kotlin 2.2.21, KSP 2.3.13, Hilt 2.61.0"
expect nonzero "Hilt in the matrix and not in the docs" "$tmp/d-ok.md" "$tmp/m-more.yml" "Hilt 2.61.0"
expect nonzero "docs without a toolchain row" "$tmp/d-empty.md" "$tmp/m-ok.yml" "no supported toolchain rows"
expect nonzero "matrix without entries" "$tmp/d-ok.md" "$tmp/m-empty.yml" "no matrix entries"
expect nonzero "matrix entry missing ksp" "$tmp/d-ok.md" "$tmp/m-broken.yml" "malformed matrix entry"
expect nonzero "missing file" "$tmp/none.md" "$tmp/m-ok.yml" "cannot read"

if [ "$fails" -eq 0 ]; then echo "all passed"; else echo "$fails failed"; exit 1; fi
