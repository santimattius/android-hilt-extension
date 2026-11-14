#!/usr/bin/env bash
# Canary for the Compose Preview workaround in app/build.gradle.kts.
#
# Usage: app/scripts/check-compose-preview-canary.sh
# AGP fails while it CREATES the Compose Preview runfiles task (project configuration), so a dry run
# of that task is enough: no device and no task execution. A control run keeps the workaround and must succeed.
# For each half of the workaround (resources, unit-tests) it then drops that half through
# -PdisableComposePreviewWorkaround and dry-runs the task again. That configuration is expected to FAIL. If it succeeds, AGP/Compose no longer needs that setting: this script exits 1 so the
# workaround (and docs/SETUP.md, docs/LIMITATIONS.md, docs/VERIFY.md) can be removed.
# It exits 2 when the build fails for a reason other than a Compose Preview failure.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
status=0
if ! "$ROOT/gradlew" -p "$ROOT" :app:generateDebugComposePreviewRunfiles --dry-run >/dev/null 2>&1; then
  echo "UNEXPECTED: :app does not configure even with the workaround in place." >&2
  exit 2
fi
for half in resources unit-tests; do
  LOG=$(mktemp)
  if "$ROOT/gradlew" -p "$ROOT" \
      :app:generateDebugComposePreviewRunfiles --dry-run \
      "-PdisableComposePreviewWorkaround=$half" >"$LOG" 2>&1; then
    echo "CANARY TRIGGERED: without the '$half' half the task is now created without it; remove that part of the workaround." >&2
    status=1
  elif grep -q "Compose Preview failed" "$LOG"; then
    echo "ok: '$half' half is still required ($(grep -m1 'Compose Preview failed' "$LOG" | cut -c1-160)...)"
  else
    echo "UNEXPECTED: build without the '$half' half failed for another reason (last lines):" >&2
    tail -n 25 "$LOG" >&2
    [ "$status" -eq 1 ] || status=2
  fi
done
exit "$status"
