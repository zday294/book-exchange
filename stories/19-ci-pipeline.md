# 19 – Continuous integration (GitHub Actions)

## Story
As a developer, I want every pull request to be built and tested automatically so that broken changes don't reach `main`.

## Depends on
18, plus the backend (02) and frontend (06) scaffolds.

## Details
Workflows in `.github/workflows/`, triggered on pull requests and pushes to `main`, using path filters so backend-only changes don't run frontend jobs and vice versa.

- **Backend job:** set up the chosen JDK, cache Maven/Gradle, run build + tests. Integration tests that need Postgres use Testcontainers (Docker is available on GitHub runners) or a Postgres service container.
- **Frontend job:** set up Node, cache npm, `npm ci`, lint, type-check, unit tests, `npm run build`, and the Node server's own tests (story 24).
- **Database script check (optional):** run `db/init.sql` against a throwaway Postgres to prove it applies cleanly.
- Publish test reports as job output/artifacts.
- Dependency updates: enable Dependabot for Maven/Gradle, npm, GitHub Actions and Dockerfiles.
- Required status checks on `main` reference these jobs (story 18).

## Acceptance criteria
- [ ] A PR that breaks a backend or frontend test fails its check.
- [ ] A clean PR passes both jobs in a reasonable time (target under ~5 minutes with caching).
- [ ] Path filters work: a docs-only change doesn't run the app builds (or the required checks are still satisfied; note the GitHub gotcha that skipped required checks can block merges, and handle it).
- [ ] Dependabot opens update PRs.
