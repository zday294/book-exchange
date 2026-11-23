# Christmas Book Exchange

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

**Database:**
```bash
docker compose up -d db
```
Postgres listens on `localhost:5433` (database, user and password are all `bookexchange` for local use; override with `POSTGRES_PORT` / `POSTGRES_PASSWORD`). It starts empty: the backend creates the schema with Liquibase on startup. To start over with an empty database: `docker compose down -v`.

**Backend** (needs Java 21): see [backend/README.md](backend/README.md).

Front end instructions will be added when that issue is completed.

## Working on it
- Pick an issue; each lists the issues it depends on.
- Work on a short-lived branch and open a pull request into `main`. `main` should always be deployable.
- **Database changes go through Liquibase only** (see [backend/README.md](backend/README.md)). Never edit the schema by hand or with Hibernate auto-DDL, and never edit a changeset that has been merged.
- Never commit secrets (passwords, keys, `.env` files). Use `.env.example` for documenting settings.

## License
No license has been chosen yet, so all rights are reserved by default.
