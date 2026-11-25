#!/usr/bin/env bash
# Starts the whole stack with docker compose and checks it the way a person would:
#   - all three services become healthy,
#   - the front end serves the page at /,
#   - /api/exchanges/XMAS2026 works THROUGH the front end's proxy (so the backend, database and
#     Liquibase changelog all work together),
#   - the backend and front end containers run as a non-root user.
# Always prints the container logs on failure and always removes the containers and volumes afterwards.
#
# Usage:  .github/scripts/smoke-test.sh            # uses images that already exist (CI builds them first)
#         SMOKE_BUILD=true .github/scripts/smoke-test.sh   # builds them first (handy locally)
# Ports and the project name can be changed (FRONTEND_PORT, BACKEND_PORT, POSTGRES_PORT, SMOKE_PROJECT).
set -uo pipefail

cd "$(dirname "$0")/../.."

# A project name of its own, so a local run never touches your own "book-exchange" containers and data volume.
project="${SMOKE_PROJECT:-book-exchange-smoke}"
export FRONTEND_PORT="${FRONTEND_PORT:-3000}" BACKEND_PORT="${BACKEND_PORT:-8080}" POSTGRES_PORT="${POSTGRES_PORT:-5433}"
compose=(docker compose -p "$project")
index="$(mktemp)"
failed=0

cleanup() {
  if [ "$failed" -ne 0 ]; then
    echo "::group::Container status"; "${compose[@]}" ps -a; echo "::endgroup::"
    echo "::group::Container logs"; "${compose[@]}" logs --no-color --tail=300; echo "::endgroup::"
  fi
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1
  rm -f "$index"
}
trap cleanup EXIT

fail() { echo "::error::$*"; failed=1; exit 1; }

up_args=(up -d --wait --wait-timeout 240)
if [ "${SMOKE_BUILD:-false}" = "true" ]; then up_args+=(--build); else up_args+=(--no-build); fi

echo "Starting the stack..."
"${compose[@]}" "${up_args[@]}" || fail "the stack did not become healthy (see the container logs)"
echo "OK: db, backend and front end are healthy"

front="http://127.0.0.1:${FRONTEND_PORT}"

status="$(curl -s -o "$index" -w '%{http_code}' "$front/")"
[ "$status" = "200" ] || fail "front end answered $status for / (expected 200)"
grep -qi '<div id="app"' "$index" || fail "front end / does not look like the app (no #app element)"
echo "OK: front end serves /"

body="$(curl -s -w '\n%{http_code}' "$front/api/exchanges/XMAS2026")"
status="${body##*$'\n'}"
[ "$status" = "200" ] || fail "/api/exchanges/XMAS2026 through the front end answered $status (expected 200): $body"
grep -q '"code":"XMAS2026"' <<<"$body" || fail "the exchange response does not contain the seeded exchange: $body"
echo "OK: /api/exchanges/XMAS2026 works through the front end's proxy"

for service in backend frontend; do
  uid="$("${compose[@]}" exec -T "$service" id -u | tr -d '[:space:]')"
  [ -n "$uid" ] && [ "$uid" != "0" ] || fail "the $service container runs as root (uid '$uid')"
  echo "OK: $service runs as uid $uid (not root)"
done

echo "Smoke test passed."
