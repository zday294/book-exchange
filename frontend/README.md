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
npm run lint       # ESLint (Vue + TypeScript rules for likely mistakes; no formatting rules). CI runs it before the tests
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
- `src/stores/participant.ts` who the visitor says they are (name only, saved in `localStorage`; names are trimmed and must be 1 to 100 characters). `components/NameDialog.vue` is the prompt: pages whose route has `meta: { requiresName: true }` (the exchange and submit pages) show only the prompt until a name is saved; the header's "Not you? Change name" reopens it. Any new page that acts as a participant should set that route meta.
- `src/wrapping/` the wrapping papers, drawn as SVG (no image files):
  - `papers.ts` the list of papers (key, label, pattern type, colors) and `resolvePaper()`, which falls back to the default for an unknown key.
  - `WrappedPresent.vue` draws a wrapped *book* (a rectangle in the cover's proportions, ribbon crossing in the middle, bow where the bands meet) and an optional name tag, at any size: `<WrappedPresent paper="holly" tag="Alex" :aspect="0.67" />`. `aspect` is width / height; it is clamped to 0.5 to 1.6 and a typical paperback shape (0.67) is used when it is missing. The exchange page gets it from the book's `coverAspectRatio` and reshapes a present live on the `book-shaped` event. The gallery at `/dev/papers` shows a range of proportions.
  - `WrappingPaperPicker.vue` radio group of all papers, used with `v-model` (emits the key).
  - **The keys must match the backend's list** (`backend/.../book/WrappingPapers.java`); a test compares them. To add a paper: add it to both lists, then add a pattern in `WrappedPresent.vue` if it needs a new design.
  - `npm run dev` then open http://localhost:5173/dev/papers for a gallery of every paper (development only; not in production builds).
- `src/styles/theme.css` festive colors, fonts and base styles shared by all pages.
- `src/components/HostSection.vue` is the host-only area at the bottom of the exchange page (shown only when the server says `isHost`). Each kind of host control is its own block component listed there (`HostMode.vue`, the submit/claim switch, and `HostSettings.vue`); to add one, write the block and list it in `HostSection.vue`. `lib/hostToken.ts` keeps the host token (shown once when an exchange is created) per exchange in `localStorage`; `getExchange` and `updateExchange` send it as `X-Host-Token`. Having a token saved doesn't make anyone the host: the server decides.
- `src/views/` one component per route. `HomeView.vue` is the home page: a "Have a code?" box (checks the code first, ignores case and spaces), a Create button, a "Continue to ..." shortcut (`lib/lastExchange.ts`), and the searchable, paged list of public exchanges (`components/ExchangeCard.vue`; open ones first, closed ones marked, with a "Hide closed exchanges" toggle). `ExchangeView.vue` is the exchange page: the tree, the pile (one `components/BookPresent.vue` per book) and the viewer's own status. It listens for live updates (`lib/liveUpdates.ts`, Server-Sent Events): new presents, claims and mode changes show at once, then the page re-reads the true state a moment later (so a claimer's own link appears, and a burst of events costs one read). While the live connection is open there is no polling; if it drops or the browser has no `EventSource`, the page re-reads the pile every 5 seconds, and when the connection returns it re-reads everything to catch what it missed (a refused connection is retried after a growing pause). Claiming: an unclaimed present is a button; clicking it opens `ConfirmClaimDialog`, then `claimBook` is called and `ClaimedRevealDialog` shows what was won. `lib/claim.ts` holds the "can this person claim this present" rules (the backend still decides and answers 409). The party: `components/PartyQueue.vue` is the queue shown on the right of the pile (on a narrow screen it moves above it), `components/HostParty.vue` the host's start/skip/end block, `api/party.ts` the calls and types; the page keeps the queue from `party-changed` events and counts the current turn down on this device from the server's number. The live connection carries the person's name (`?name=`), which is how the server knows who is online. `components/ModalDialog.vue` is the shared accessible dialog (focus kept inside, Escape, focus returned). `lib/layout.ts` gives each present a small tilt and offset from its id, so the pile looks hand-placed but never jumps. `SubmitBookView.vue` is the form to add a book: it checks the fields before sending (`lib/submitValidation.ts`, with `lib/bookLink.ts` mirroring the backend's book-link (Amazon or Barnes & Noble) rule, so keep the allowed hosts in step with `BOOK_LINK_ALLOWED_HOSTS`) and shows the backend's per-field errors.

Node note: tests install a small in-memory `localStorage` (`src/test-setup.ts`) because newer Node versions ship an experimental global one that conflicts with jsdom.
