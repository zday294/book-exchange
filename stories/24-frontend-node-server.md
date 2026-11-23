# 24 – Front-end Node.js server

## Story
As a developer, I want the front end to run on its own Node.js server so that it can be deployed independently from the Spring backend and the browser has a single origin to talk to.

## Decision: what kind of Node server?
Two common shapes, which we should settle at the start of this story:

| Option | What it is | Fit |
|---|---|---|
| **Small Node server (Express or Fastify) serving the built Vue app and proxying `/api`** (recommended) | Vue stays a normal single-page app. The Node server hands out the static files, returns `index.html` for page routes, and forwards `/api/**` to Spring. | Simple, small, easy to reason about; matches what the app needs. |
| **Nuxt (server-side rendering)** | Vue framework whose Node server renders pages on the server. | Worth it for SEO or first-load speed on public pages. This app is a private, interactive group tool, so it adds complexity with little payoff. |

Recommendation: the small Express/Fastify server. If you intended server-side rendering, say so and this story changes to a Nuxt migration.

## Details (for the recommended option)
- Location: `frontend/server/` (or a sibling folder). TypeScript or modern JavaScript on a current Node LTS.
- **Static files:** serves the Vite build output with sensible caching (long cache for hashed assets, no-cache for `index.html`) and gzip/brotli compression.
- **SPA fallback:** any non-`/api`, non-file GET returns `index.html` so Vue Router URLs like `/exchange/K7M2QX` work on refresh and when shared.
- **API proxy:** forwards `/api/**` to the backend (`BACKEND_URL` environment variable) including the `X-Participant-Name` header. The browser only sees one origin, so no CORS setup is needed in production (story 02's CORS setting remains for local dev).
- **Live updates:** the proxy must pass Server-Sent Events (story 15) straight through: no response buffering or compression on `text/event-stream`, no short idle timeout, and client disconnects must close the upstream stream.
- Security basics: `helmet`-style security headers, no leaking of upstream error details, request size limits.
- `GET /healthz` for container health checks.
- Configuration by environment variables only (`PORT`, `BACKEND_URL`).
- Local development: `npm run dev` (Vite dev server with proxy) stays the day-to-day way to work; `npm start` runs the production Node server against a built app.
- Tests: a few server tests (static serving, SPA fallback, proxy forwards headers and status codes, SSE passes through).

## Acceptance criteria
- [ ] `npm run build && npm start` serves the app, and deep links such as `/exchange/<code>` load on refresh.
- [ ] `/api/**` requests reach the backend with headers intact and responses returned unchanged (including error statuses).
- [ ] A live-update stream through the server delivers events immediately (not batched) and ends cleanly when the browser disconnects.
- [ ] `/healthz` returns 200.
- [ ] Backend URL and port are configurable only through environment variables.
- [ ] Server tests run in CI (story 19).

## Notes
- Hosting impacts (second Cloud Run service, service-to-service access) are covered in stories 20–23.
