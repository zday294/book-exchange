# Front end (Vue 3)

Vue 3 + Vite + TypeScript, Vue Router, Pinia. In production the built app is served by a small Node server (`server/`) that also proxies `/api` to the backend.

## Run it
Start the backend first (see `../backend/README.md`; it listens on port 8080), then:
```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```
`/api/**` is proxied to the backend, so the browser sees a single origin (no CORS needed in dev). The home page shows whether the backend is reachable.

## Production server
```bash
npm run build      # builds the Vue app into dist/
npm start          # serves dist/ and proxies /api to the backend
```
Configured only by environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Port to listen on |
| `BACKEND_URL` | `http://localhost:8080` | Backend that `/api/**` is forwarded to |

What it does (`server/app.js`):
- Serves `dist/`: hashed files under `/assets` are cached for a year, everything else is revalidated; gzip/deflate compression for responses that benefit.
- Returns `index.html` for client-side routes (so `/exchange/<code>` works on refresh and when shared); missing files are 404.
- Forwards `/api/**` with headers, status codes and bodies unchanged. Responses are streamed (not buffered or compressed) and there is no idle timeout, so Server-Sent Events (#15) pass straight through; a closed browser tab closes the upstream request. If the backend is down it answers `502` with a generic message.
- Security headers (helmet), request bodies over 1 MB rejected with `413` (by `Content-Length`), `GET /healthz` for container health checks (does not call the backend).
- Shuts down gracefully on `SIGTERM`/`SIGINT` (waits up to 10 s for open streams).

## Other commands
```bash
npm test           # app tests (Vitest + jsdom) and server tests
npm run build      # type-check and build static files into dist/
```

## Configuration
Copy `.env.example` to `.env.local` to override:

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` | Where the dev proxy sends `/api` (development only) |
| `VITE_API_BASE_URL` | empty (same origin) | API base URL the browser uses |

## Layout
- `server/` the production Node server and its tests (plain ES modules, no build step)
- `src/router/` routes: `/`, `/exchanges/new`, `/exchange/:exchangeCode`, `/exchange/:exchangeCode/submit`
- `src/api/` `client.ts` is the only place that calls `fetch`: JSON in/out, adds `X-Participant-Name` when a name is saved, throws `ApiError` (with per-field errors for forms). One module per resource next to it.
- `src/stores/participant.ts` who the visitor says they are (name only, saved in `localStorage`). The name-entry gate is story #8.
- `src/styles/theme.css` festive colors, fonts and base styles shared by all pages.
- `src/views/` one component per route (placeholders until their stories).

Node note: tests install a small in-memory `localStorage` (`src/test-setup.ts`) because newer Node versions ship an experimental global one that conflicts with jsdom.
