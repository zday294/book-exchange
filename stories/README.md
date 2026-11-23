# Christmas Book Exchange – Stories

Stack: Java Spring Boot backend, Vue 3 front end served by its own Node.js server, PostgreSQL.

## Suggested build order

| # | Story | Area |
|---|---|---|
| 01 | [Database schema script](01-database-schema.md) | DB |
| 02 | [Spring Boot backend scaffold](02-backend-scaffold.md) | Backend |
| 12 | [Create a book exchange](12-create-exchange.md) | Backend + Frontend |
| 13 | [Find book exchanges](13-find-exchanges.md) | Backend + Frontend |
| 03 | [Submit a book endpoint](03-submit-book-endpoint.md) | Backend |
| 04 | [Retrieve books endpoints](04-retrieve-books-endpoint.md) | Backend |
| 05 | [Claim a book endpoint](05-claim-book-endpoint.md) | Backend |
| 14 | [Book cover lookup](14-book-cover-lookup.md) | Backend |
| 15 | [Live updates (SSE)](15-live-updates.md) | Backend + Frontend |
| 06 | [Vue front-end scaffold](06-frontend-scaffold.md) | Frontend |
| 24 | [Front-end Node.js server](24-frontend-node-server.md) | Frontend |
| 10 | [Wrapping paper resources](10-wrapping-paper-resources.md) | Frontend (+ small backend list) |
| 08 | [Participant name entry and persistence](08-participant-identity.md) | Frontend |
| 07 | [Submit book page](07-submit-book-page.md) | Frontend |
| 09 | [Book exchange page: tree and pile](09-exchange-page-tree-and-pile.md) | Frontend |
| 11 | [Claim a book from the pile (UI)](11-claim-book-ui.md) | Frontend |
| 16 | [Unwrapping animation](16-unwrap-animation.md) | Frontend — upgrade |
| 17 | [Optional sign-in and history](17-optional-sign-in.md) | Future |
| 18 | [Put the project on GitHub](18-github-repository.md) | Repo — do first |
| 19 | [Continuous integration (GitHub Actions)](19-ci-pipeline.md) | Repo / CI |
| 20 | [Deployment strategy decision (Google Cloud)](20-deployment-strategy.md) | Infra — spike |
| 21 | [Containerize the backend and local stack](21-containerize-apps.md) | Infra |
| 22 | [Provision Google Cloud infrastructure](22-provision-gcp.md) | Infra |
| 23 | [Continuous deployment from GitHub](23-continuous-deployment.md) | Infra |

Backend stories (02–05, 12–14) and frontend scaffold/resources (06, 10) can proceed in parallel once 01 is done. Stories 15–16 can follow the first working version; story 09 is fine with a simple refresh until 15 lands. Story 17 is not scheduled.

Story 18 (GitHub) is best done first, before any code. Story 19 (CI) follows once the scaffolds (02, 06) exist. Story 20 (deployment decision) can run in parallel with feature work, and its answer about live updates across multiple instances may affect story 15. Stories 21–23 follow the decision.

In API paths and URLs, `{exchangeCode}` is the exchange's short shareable `code`, not its numeric id.

## Confirmed decisions
1. **Exchanges:** a seeded default exchange exists for now; creating and finding exchanges are stories 12 and 13.
2. **Visibility:** unclaimed books reveal nothing. Once claimed, everyone sees the title, cover and who claimed it. The **Amazon link is only ever revealed to the claimer**. Covers come from the Amazon link's book identity (story 14).
3. **No claiming your own submission.**
4. **Identity** is name-only (localStorage + `X-Participant-Name`, case-insensitive) for now; optional sign-in with history is a future story (17).
5. **Live updates:** claims and new books appear on every open browser automatically (story 15, Server-Sent Events), with an unwrapping animation as a later upgrade (story 16).
6. **One claim per person per exchange**, enforced in the database as well as the service layer.
7. **Front end has its own Node.js server** (small Express/Fastify server serving the Vue build and proxying `/api`; story 24), deployed as its own service.
8. Wrapping paper is drawn as **SVG** components; the backend stores only a paper key.
