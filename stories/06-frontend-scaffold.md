# 06 – Vue front-end scaffold

## Story
As a developer, I want a runnable Vue 3 app with routing and an API client so that page stories have a foundation.

## Details
- Directory: `frontend/`. Vue 3 + Vite + TypeScript, Vue Router, Pinia.
- Routes:
  - `/` → home: find or create an exchange (stories 12 and 13)
  - `/exchanges/new` → create-exchange page (story 12)
  - `/exchange/:exchangeCode` → book-exchange page (story 09)
  - `/exchange/:exchangeCode/submit` → submit-a-book page (story 07)
- Thin API client module (`fetch` or axios) with base URL from `VITE_API_BASE_URL`; automatically adds the `X-Participant-Name` header when a name is stored (story 08).
- Vite dev proxy to the Spring backend during development (`npm run dev`).
- **Own Node.js server** for production: see story 24. The Vue app is built to static files, and a small Node server serves them and forwards `/api/**` to the backend.
- Basic global styling / festive theme variables (colors, fonts) so later pages share a look.
- Basic layout shell with a header and nav links between the two pages.

## Acceptance criteria
- [ ] `npm run dev` serves the app and the routes above render placeholders.
- [ ] API client can call the backend health or list endpoint successfully.
- [ ] Folder layout leaves room for the Node server (e.g. `frontend/` = Vue app, `frontend/server/` or a sibling `frontend-server/` = Node server; decided in story 24).
- [ ] README in `frontend/` documents how to run it.
