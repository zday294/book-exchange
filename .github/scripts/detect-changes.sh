#!/usr/bin/env bash
# Decides which parts of CI a change needs, from the files it touches.
# Usage: detect-changes.sh <base-sha> <head-sha>      (prints "backend=true|false" and "frontend=true|false")
set -euo pipefail

base="$1"
head="$2"
files="$(git diff --name-only "$base" "$head")"

backend=false
frontend=false

# Pattern notes:
#  - A workflow or CI-script change re-runs everything, so a broken CI change is caught by CI itself.
#  - The front end has a test that reads the backend's list of wrapping paper keys, so it must also
#    run when that one file changes.
if grep -Eq '^(backend/|\.github/workflows/|\.github/scripts/)' <<<"$files"; then backend=true; fi
if grep -Eq '^(frontend/|\.github/workflows/|\.github/scripts/|backend/src/main/java/com/bookexchange/book/WrappingPapers\.java$)' <<<"$files"; then frontend=true; fi

echo "backend=$backend"
echo "frontend=$frontend"
