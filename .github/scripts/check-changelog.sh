#!/usr/bin/env bash
# Liquibase checksums every changeset it has applied, so a merged changeset must never be edited,
# renamed or deleted: add a NEW changeset instead. New files are fine.
# Usage: check-changelog.sh <base-sha> <head-sha>
set -euo pipefail

base="$1"
head="$2"
dir="backend/src/main/resources/db/changelog/changes/"

# M = modified, D = deleted, R = renamed (A = added is allowed)
touched="$(git diff --name-status --diff-filter=MDR "$base" "$head" -- "$dir")"

if [ -n "$touched" ]; then
  echo "::error::A Liquibase changeset that already exists was modified, renamed or deleted:"
  echo "$touched"
  echo "Never edit a merged changeset (see backend/README.md). Add a new changeset instead."
  exit 1
fi
echo "OK: no existing changeset was changed."
