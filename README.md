# Christmas Book Exchange

[![CI](https://github.com/zday294/book-exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/zday294/book-exchange/actions/workflows/ci.yml)

A web app for running a Christmas book exchange. People submit a wrapped book (their name, the book's name, an Amazon link, and a wrapping paper). Everyone gathers around a tree with a pile of presents, and each person claims one. A claimed book reveals its title, cover and claimer; only the claimer ever sees the Amazon link.

> **Status:** planning. The work is tracked as [GitHub issues](https://github.com/zday294/book-exchange/issues); only the database and backend scaffold exist so far.

## Stack
- **Backend:** Java 21, Spring Boot, Liquibase for schema changes
- **Front end:** Vue 3, served by its own Node.js server
- **Database:** PostgreSQL
- **Hosting (planned):** Google Cloud (Cloud Run, Cloud SQL)

## Repository layout (planned)
```
backend/    Spring Boot API
frontend/   Vue app and its Node.js server
infra/      Infrastructure as code
.github/    Workflows and templates
```

## Running locally
Prerequisite: Docker.

**Everything in containers (one command):**
```bash
docker compose up --build
```
Then open http://localhost:3000. This starts Postgres, the backend (http://localhost:8080, Swagger UI at `/swagger-ui.html`) and the front end's Node server, which also forwards `/api` to the backend. After changing code, run it again with `--build`. Stop with `docker compose down` (add `-v` to also delete the data).

Ports can be changed with `FRONTEND_PORT`, `BACKEND_PORT` and `POSTGRES_PORT` (for example `FRONTEND_PORT=3001 docker compose up --build`).

| Image | Size on disk | Time to start |
|---|---|---|
| backend (JRE 21 + Spring Boot jar) | about 600 MB | about 8 s to "Started" after the container begins; about 25 s from `up` until all three services report healthy on a fresh database |
| front end (Node 22 slim + server + built app) | about 360 MB | under 2 s |

Measured locally on a laptop, uncompressed. First build takes about 2 minutes (dependencies are cached after that). These feed the cold-start decision in #20.

Both containers run as non-root users and contain no credentials: settings come from environment variables (the compose file supplies throwaway local values).

**Or run the pieces yourself** (e.g. from your IDE) with only the database in Docker:

**Database:**
```bash
docker compose up -d db
```
Postgres listens on `localhost:5433` (database, user and password are all `bookexchange` for local use; override with `POSTGRES_PORT` / `POSTGRES_PASSWORD`). It starts empty: the backend creates the schema with Liquibase on startup. To start over with an empty database: `docker compose down -v`.

**Backend** (needs Java 21): see [backend/README.md](backend/README.md).

**Front end** (needs Node 22+): see [frontend/README.md](frontend/README.md). `npm run dev` serves it on http://localhost:5173 and proxies `/api` to the backend.

## Continuous integration
Every pull request (and every push to `main`) is built and tested by GitHub Actions (`.github/workflows/ci.yml`):

| Job | Runs when | What it does |
|---|---|---|
| Backend | `backend/` changes | `./mvnw verify` on Java 21: all tests, with a throwaway Postgres (Testcontainers) that also applies the whole Liquibase changelog |
| Front end | `frontend/` changes, or the backend's wrapping paper list changes | type-check, the Vue and Node server tests, production build (Node 22) |
| Liquibase changesets are append-only | every pull request | fails if a changeset that is already in `main` is edited, renamed or deleted |
| **CI result** | always | one summary check that passes when everything that ran passed (skipped jobs count as fine) |

Changes to the workflows or `.github/scripts/` re-run everything; pushes to `main` always run everything. Test reports are kept as run artifacts for 7 days, and the job summary shows the test counts. To run the same checks locally: `cd backend && ./mvnw verify` and `cd frontend && npm ci && npm run typecheck && npm test && npm run build`.

Dependabot (`.github/dependabot.yml`) opens weekly pull requests for Maven, npm, GitHub Actions and Docker updates (minor and patch updates grouped). The actions in the workflow are pinned to commit SHAs, which Dependabot keeps current.

**Requiring the checks:** in the repository's branch protection for `main`, require the status check named **CI result**. That single check is enough: it covers all the jobs above, even when the change detection skips some.

## Working on it
- Pick an issue; each lists the issues it depends on.
- Work on a short-lived branch and open a pull request into `main`. `main` should always be deployable.
- **Database changes go through Liquibase only** (see [backend/README.md](backend/README.md)). Never edit the schema by hand or with Hibernate auto-DDL, and never edit a changeset that has been merged.
- Never commit secrets (passwords, keys, `.env` files). Use `.env.example` for documenting settings.

## License
No license has been chosen yet, so all rights are reserved by default.
