# Front end (Vue 3)

Vue 3 + Vite + TypeScript, Vue Router, Pinia. The production Node server that serves the built app and proxies `/api` is a separate story (#24); it will live in `frontend/server/`.

## Run it
Start the backend first (see `../backend/README.md`; it listens on port 8080), then:
```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```
`/api/**` is proxied to the backend, so the browser sees a single origin (no CORS needed in dev). The home page shows whether the backend is reachable.

## Other commands
```bash
npm test           # unit tests (Vitest + jsdom)
npm run build      # type-check and build static files into dist/
```

## Configuration
Copy `.env.example` to `.env.local` to override:

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` | Where the dev proxy sends `/api` (development only) |
| `VITE_API_BASE_URL` | empty (same origin) | API base URL the browser uses |

## Layout
- `src/router/` routes: `/`, `/exchanges/new`, `/exchange/:exchangeCode`, `/exchange/:exchangeCode/submit`
- `src/api/` `client.ts` is the only place that calls `fetch`: JSON in/out, adds `X-Participant-Name` when a name is saved, throws `ApiError` (with per-field errors for forms). One module per resource next to it.
- `src/stores/participant.ts` who the visitor says they are (name only, saved in `localStorage`). The name-entry gate is story #8.
- `src/styles/theme.css` festive colors, fonts and base styles shared by all pages.
- `src/views/` one component per route (placeholders until their stories).

Node note: tests install a small in-memory `localStorage` (`src/test-setup.ts`) because newer Node versions ship an experimental global one that conflicts with jsdom.
