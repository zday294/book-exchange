# 21 – Containerize the backend and front-end server (and local stack)

## Story
As a developer, I want the backend packaged as a container image, and a one-command local stack, so that what runs locally matches what runs in the cloud.

## Depends on
02; informed by 20.

## Details
- `backend/Dockerfile`: multi-stage build (build with the JDK image, run on a slim JRE image), non-root user, container-aware JVM memory settings, port taken from the `PORT` environment variable (Cloud Run requirement).
- `.dockerignore` files to keep images small and secrets out.
- Configuration entirely through environment variables (DB URL/user/password, allowed CORS origins, allowed Amazon hosts, etc.); no environment-specific files baked in.
- `docker-compose.yml` at the repo root: Postgres (running `db/init.sql`), the backend and the front-end Node server, so `docker compose up` gives a working app.
- Front end: a second multi-stage `Dockerfile` that builds the Vue app and runs the Node server from story 24 (slim Node image, non-root user, production dependencies only, `PORT` from the environment). The backend URL is supplied via an environment variable.
- Graceful shutdown enabled so in-flight requests and SSE streams close cleanly on deploy.

## Acceptance criteria
- [ ] `docker build` produces a working image, and the image runs as a non-root user.
- [ ] `docker compose up` starts DB, backend and front-end server; the backend health endpoint returns `UP` and the app loads in the browser on the front-end server's port.
- [ ] Both images run as non-root users.
- [ ] No credentials are baked into the image.
- [ ] Image size and startup time are recorded in the README (feeds the cold-start decision in story 20).
