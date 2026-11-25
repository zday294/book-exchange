#!/usr/bin/env bash
# Decides which parts of CI a change needs, from the files it touches.
# Usage: detect-changes.sh <base-sha> <head-sha>      (prints "backend=", "frontend=", "docker=" and "database=" as true|false)
set -euo pipefail

base="$1"
head="$2"
files="$(git diff --name-only "$base" "$head")"

backend=false
frontend=false
docker=false
database=false

# Pattern notes:
#  - A workflow or CI-script change re-runs everything, so a broken CI change is caught by CI itself.
#  - The front end has a test that reads the backend's list of wrapping paper keys, so it must also
#    run when that one file changes.
if grep -Eq '^(backend/|\.github/workflows/|\.github/scripts/)' <<<"$files"; then backend=true; fi
if grep -Eq '^(frontend/|\.github/workflows/|\.github/scripts/|backend/src/main/java/com/bookexchange/book/WrappingPapers\.java$)' <<<"$files"; then frontend=true; fi

#  - The Docker images and the compose smoke test only matter when something that goes into an image
#    changes: the Dockerfiles, the compose file, the dependency manifests, the Node server and the Maven wrapper.
#  - The Liquibase upgrade/rollback check only matters when the changelog changes.
if grep -Eq '^(backend/Dockerfile|frontend/Dockerfile|docker-compose\.yml|\.dockerignore|backend/\.dockerignore|frontend/\.dockerignore|frontend/package(-lock)?\.json|backend/pom\.xml|backend/\.mvn/|backend/mvnw|frontend/server/|\.github/workflows/|\.github/scripts/)' <<<"$files"; then docker=true; fi
if grep -Eq '^(backend/src/main/resources/db/|\.github/workflows/|\.github/scripts/)' <<<"$files"; then database=true; fi

echo "backend=$backend"
echo "frontend=$frontend"
echo "docker=$docker"
echo "database=$database"
