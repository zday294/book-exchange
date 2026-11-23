# Christmas Book Exchange

A web app for running a Christmas book exchange. People submit a wrapped book (their name, the book's name, an Amazon link, and a wrapping paper). Everyone gathers around a tree with a pile of presents, and each person claims one. A claimed book reveals its title, cover and claimer; only the claimer ever sees the Amazon link.

> **Status:** planning. The work is tracked as [GitHub issues](https://github.com/zday294/book-exchange/issues); no application code exists yet.

## Stack
- **Backend:** Java, Spring Boot
- **Front end:** Vue 3, served by its own Node.js server
- **Database:** PostgreSQL
- **Hosting (planned):** Google Cloud (Cloud Run, Cloud SQL)

## Repository layout (planned)
```
backend/    Spring Boot API
frontend/   Vue app and its Node.js server
db/         SQL scripts
infra/      Infrastructure as code
.github/    Workflows and templates
```

## Running locally
Not available yet. Prerequisites and run instructions will be added as the issues for the database, backend and front end are completed.

## Working on it
- Pick an issue; each lists the issues it depends on.
- Work on a short-lived branch and open a pull request into `main`. `main` should always be deployable.
- Never commit secrets (passwords, keys, `.env` files). Use `.env.example` for documenting settings.

## License
No license has been chosen yet, so all rights are reserved by default.
