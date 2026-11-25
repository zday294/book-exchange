#!/usr/bin/env bash
# Checks a pull request's Liquibase changelog on a throwaway Postgres, beyond "it applies to an empty database":
#   1. UPGRADE   the changelog from the base commit is applied, then the pull request's changelog on top of it
#                (new changesets must apply on an existing schema; an edited merged changeset fails on its checksum);
#   2. ROLLBACK  the changesets the pull request added are rolled back with their own --rollback statements, and the
#                schema must be exactly what the base commit had (so a missing or wrong rollback is found now,
#                not during an incident);
#   3. RE-APPLY  they are applied again, and the schema must match the upgraded one.
# The whole changelog is also validated, and everything is cleaned up afterwards.
#
# Usage: check-liquibase.sh <base-sha>            (run from anywhere inside the repository; uses the checked-out files as the pull request)
# Needs Docker. Run it locally the same way to reproduce a CI failure.
set -uo pipefail

base="${1:?usage: check-liquibase.sh <base-sha>}"
cd "$(git rev-parse --show-toplevel)"

LIQUIBASE_IMAGE="${LIQUIBASE_IMAGE:-liquibase/liquibase:4.31.1}"   # the version Spring Boot's Liquibase is closest to
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17}"                   # the same major version as docker-compose.yml
resources="backend/src/main/resources"
suffix="$$-$RANDOM"
network="lbcheck-$suffix"
db="lbcheck-db-$suffix"
work="$(mktemp -d)"

cleanup() {
  docker rm -f "$db" >/dev/null 2>&1
  docker network rm "$network" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

fail() { echo "::error::$*"; exit 1; }

# Liquibase against the database, with the changelog taken from the given resources folder.
liquibase() {
  local dir="$1"; shift
  docker run --rm --network "$network" -v "$dir:/liquibase/changelog:ro" "$LIQUIBASE_IMAGE" \
    --url="jdbc:postgresql://$db:5432/bookexchange" --username=bookexchange --password=bookexchange \
    --search-path=/liquibase/changelog --changelog-file=db/changelog/db.changelog-master.yaml "$@"
}

applied() {
  docker exec "$db" psql -U bookexchange -d bookexchange -Atc "select count(*) from databasechangelog"
}

schema() {
  # pg_dump writes a random \restrict token on every run; drop those lines so identical schemas compare equal.
  docker exec "$db" pg_dump -U bookexchange -d bookexchange --schema-only --no-owner --exclude-table='databasechangelog*' \
    | grep -v -E '^\\(un)?restrict '
}

# The pull request's changelog, and the base commit's changelog (exported from git).
head_dir="$work/head"
base_dir="$work/base"
mkdir -p "$head_dir" "$base_dir"
cp -R "$resources/db" "$head_dir/db"
if git cat-file -e "$base^{commit}" 2>/dev/null && git ls-tree -r --name-only "$base" -- "$resources/db" | grep -q .; then
  git archive "$base" -- "$resources/db" | tar -x -C "$base_dir" --strip-components=4
else
  echo "The base commit has no changelog: treating this as the first one."
  mkdir -p "$base_dir/db/changelog"
  printf 'databaseChangeLog: []\n' > "$base_dir/db/changelog/db.changelog-master.yaml"
fi

docker network create "$network" >/dev/null || fail "could not create a docker network"
docker run -d --name "$db" --network "$network" \
  -e POSTGRES_DB=bookexchange -e POSTGRES_USER=bookexchange -e POSTGRES_PASSWORD=bookexchange \
  "$POSTGRES_IMAGE" >/dev/null || fail "could not start Postgres"
for _ in $(seq 1 60); do
  docker exec "$db" pg_isready -U bookexchange -d bookexchange >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$db" pg_isready -U bookexchange -d bookexchange >/dev/null 2>&1 || fail "Postgres did not start"
# Postgres restarts once during first start-up; wait until it stays up.
sleep 3
docker exec "$db" pg_isready -U bookexchange -d bookexchange >/dev/null 2>&1 || fail "Postgres did not stay up"

echo "== 1. Upgrade: base changelog, then this pull request's changelog on the same database"
liquibase "$base_dir" update >"$work/base-update.log" 2>&1 || { cat "$work/base-update.log"; fail "the base commit's own changelog does not apply (is the base broken?)"; }
before_count="$(applied)"
schema >"$work/schema-base.sql"
liquibase "$head_dir" validate >"$work/validate.log" 2>&1 || { cat "$work/validate.log"; fail "liquibase validate failed"; }
liquibase "$head_dir" update >"$work/upgrade.log" 2>&1 || { cat "$work/upgrade.log"; fail "the pull request's changelog does not apply on top of the existing schema"; }
after_count="$(applied)"
schema >"$work/schema-upgraded.sql"
new_count=$((after_count - before_count))
echo "OK: $before_count changesets already applied, $new_count new ones applied on top"

if [ "$new_count" -eq 0 ]; then
  echo "No new changesets: nothing to roll back. Done."
  exit 0
fi

echo "== 2. Rollback: undo the $new_count new changeset(s)"
liquibase "$head_dir" rollback-count "$new_count" >"$work/rollback.log" 2>&1 || { cat "$work/rollback.log"; fail "rolling back the new changeset(s) failed (check their --rollback statements)"; }
schema >"$work/schema-rolled-back.sql"
if ! diff -u "$work/schema-base.sql" "$work/schema-rolled-back.sql" >"$work/rollback.diff"; then
  cat "$work/rollback.diff"
  fail "after the rollback the schema is not what it was before the new changeset(s): a --rollback is missing or wrong"
fi
echo "OK: the rollback restores the previous schema exactly"

echo "== 3. Re-apply"
liquibase "$head_dir" update >"$work/reapply.log" 2>&1 || { cat "$work/reapply.log"; fail "re-applying the changeset(s) after the rollback failed"; }
schema >"$work/schema-reapplied.sql"
if ! diff -u "$work/schema-upgraded.sql" "$work/schema-reapplied.sql" >"$work/reapply.diff"; then
  cat "$work/reapply.diff"
  fail "after rollback and re-apply the schema differs from the first upgrade"
fi
echo "OK: re-applied, schema identical to the first upgrade"
echo "Liquibase checks passed."
