#!/usr/bin/env bash
# Fails when the supported toolchains in docs/SETUP.md differ from the matrix.include entries of
# .github/workflows/compat.yml. Compared as a set of "AGP, Kotlin, KSP, Hilt" combinations.
#
# Docs side: rows of the "Supported toolchains" section that start with
#   | AGP x, Kotlin y, KSP z, Hilt w |
# (the repository's own build row and the minSdk row name no KSP version and are not validated combinations).
# Matrix side: entries with agp/kotlin/hilt/ksp; the minSdk probe (an entry with `minsdk:`) is skipped on
# purpose: it asserts a rejection and its combination is already listed by a normal entry.
#
# Usage: scripts/check-supported-versions.sh [<SETUP.md> [<compat.yml>]]
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
docs_file="${1:-$root/docs/SETUP.md}"
matrix_file="${2:-$root/.github/workflows/compat.yml}"

for f in "$docs_file" "$matrix_file"; do
  [ -r "$f" ] || { echo "cannot read $f" >&2; exit 2; }
done

docs_set="$(awk '
  /^## /           { in_section = ($0 == "## Supported toolchains"); next }
  in_section && match($0, /^\| AGP [0-9][^,|]*, Kotlin [^,|]+, KSP [^,|]+, Hilt [^ |]+ \|/) {
    row = substr($0, 3, RLENGTH - 4); print row
  }' "$docs_file" | sort -u)"

matrix_set=""
while IFS= read -r line; do
  case "$line" in *minsdk:*) continue ;; esac
  field() { printf '%s' "$line" | sed -n "s/.*[{ ,]$1: *\"\([^\"]*\)\".*/\1/p"; }
  agp="$(field agp)"; kotlin="$(field kotlin)"; hilt="$(field hilt)"; ksp="$(field ksp)"
  if [ -z "$agp" ] || [ -z "$kotlin" ] || [ -z "$hilt" ] || [ -z "$ksp" ]; then
    echo "malformed matrix entry in $matrix_file: $line" >&2; exit 2
  fi
  matrix_set="$matrix_set
AGP $agp, Kotlin $kotlin, KSP $ksp, Hilt $hilt"
done < <(grep -E '^[[:space:]]*- \{.*agp:' "$matrix_file")
matrix_set="$(printf '%s\n' "$matrix_set" | sed '/^$/d' | sort -u)"

[ -n "$docs_set" ] || { echo "no supported toolchain rows found in $docs_file" >&2; exit 2; }
[ -n "$matrix_set" ] || { echo "no matrix entries found in $matrix_file" >&2; exit 2; }

only_docs="$(comm -23 <(printf '%s\n' "$docs_set") <(printf '%s\n' "$matrix_set"))"
only_matrix="$(comm -13 <(printf '%s\n' "$docs_set") <(printf '%s\n' "$matrix_set"))"

if [ -n "$only_docs" ] || [ -n "$only_matrix" ]; then
  echo "docs/SETUP.md and the compat matrix disagree:" >&2
  printf '%s\n' "$only_docs" | sed '/^$/d; s/^/  only in docs\/SETUP.md: /' >&2
  printf '%s\n' "$only_matrix" | sed '/^$/d; s/^/  only in compat.yml: /' >&2
  exit 1
fi
echo "supported toolchains match the compat matrix:"
printf '%s\n' "$docs_set" | sed 's/^/  /'
