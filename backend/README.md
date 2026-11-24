# Backend

Spring Boot 3.5 (Java 21) API, Postgres via Spring Data JPA, schema managed by Liquibase.

## Run it
Prerequisites: Java 21 and Docker.

```bash
docker compose up -d db      # from the repo root; Postgres on localhost:5433
cd backend
./mvnw spring-boot:run
```

- Health check: http://localhost:8080/actuator/health
- On startup Liquibase applies any new changesets, then Hibernate checks the entities match the schema (`ddl-auto=validate`).

### Configuration (environment variables)
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/bookexchange` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `bookexchange` / `bookexchange` | Local-dev credentials; set real ones elsewhere |
| `PORT` | `8080` | HTTP port |
| `AMAZON_ALLOWED_HOSTS` | `amazon.com,amzn.to,a.co` | Hosts a submitted book link may use (subdomains such as `www.amazon.com` are allowed too) |
| `SPRINGDOC_ENABLED` | `true` | Serves Swagger UI and the OpenAPI spec |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated browser origins allowed to call `/api/**` |

## API
### Create / get an exchange
- `POST /api/exchanges` with `{ "name", "description"?, "organizerName", "listed"? }` -> `201` + `Location`, the exchange is `OPEN` and has a generated 7-character `code` (alphabet without `0 O 1 I L`). `listed` defaults to `true`. Validation errors (`400`) name each field. Code collisions are retried automatically (check + unique index).
- `GET /api/exchanges/{exchangeCode}` -> name, description, organizer, listed, status, `bookCount`, `claimedCount`, `createdAt`; `404` for an unknown code (private exchanges are found this way too).
- Not yet: closing/editing/organizer-only actions (need sign-in, #17), and the public list (#13).

### The host, and changing an exchange's settings
There is no sign-in yet, so the host is whoever holds the **host token**:
- `POST /api/exchanges` returns a `hostToken` (43 URL-safe characters, 256 random bits) **once**. Only its SHA-256 hash is stored (`exchange.host_token_hash`), so a copy of the database can't be used to act as a host. It is never returned or logged again. Losing it means losing host controls until sign-in (#17) exists.
- Host-only calls send it as the **`X-Host-Token`** header (compared in constant time). A missing or wrong token, a token from another exchange, and any exchange without a token (those created before this existed, such as the seeded `XMAS2026`) all get `403`.
- `GET /api/exchanges/{code}` includes `isHost`: true only when the request carries the valid token. The UI shows host controls only when the server says so.
- `PATCH /api/exchanges/{code}` (host only) changes `name`, `description`, `organizerName` and/or `listed`. Fields left out are unchanged; an empty `description` removes it; validation is the same as at creation (`400` with per-field `details`). `404` for an unknown exchange.
- CORS allows the `PATCH` method and the `X-Host-Token` header.

### Live updates
`GET /api/exchanges/{code}/events` is a long-lived **Server-Sent Events** stream (`text/event-stream`) for one exchange; browsers open it with `EventSource`. `404` for an unknown exchange, `503` when the server already holds its maximum number of connections.
- `ready`: the connection is open (sent at once).
- `book-added`: `{id, submitterName, wrappingPaper}`, a new wrapped present. Nothing about the book.
- `book-claimed`: `{id, claimedByName, claimedAt, bookTitle, coverImageUrl?}`. Public once a book is claimed. **Never the book link**; that stays in the claimer's own response.
- `mode-changed`: `{mode}`.
- A comment line (`: keep-alive`) every 15 seconds keeps proxies from closing an idle connection and lets the server notice a browser that went away.
- Events are sent only **after the database transaction commits**, so a rejected or rolled-back request announces nothing. Events of one exchange never reach watchers of another. Events sent while a browser was disconnected are **not replayed**: the front end re-reads the pile when it reconnects.
- Connections last at most 30 minutes (the browser reconnects by itself), and all are closed as soon as the application starts shutting down, so a deploy is not held up by open streams.

Settings (environment variables): `EVENTS_HEARTBEAT_MILLIS` (15000), `EVENTS_MAX_SUBSCRIBERS` (1000, roughly one per open browser page), `EVENTS_CONNECTION_MINUTES` (30).

**Limits to know about for deployment (#20-#22):** connections are held in the memory of one server process, so live updates reach only the people connected to the same instance; run a single backend instance, or add a shared mechanism (for example Postgres `LISTEN/NOTIFY`) before scaling out. The hosting platform's request timeout must allow long-lived requests (for Cloud Run, raise the request timeout; the browser reconnects when it ends, so the cost of a short timeout is only more reconnects).

### Submit mode and claim mode
An exchange is in exactly one **mode**, and the two are exclusive (the backend enforces it; the UI only mirrors it):
- **`SUBMIT`** (the default for every exchange, including existing ones): participants can submit books. **Claiming returns `409`** ("Claiming hasn't started yet; the host will open it when everyone has submitted their book").
- **`CLAIM`**: participants can claim books. **Submitting returns `409`** ("This exchange has moved on to claiming, so no more books can be submitted"). Nothing is saved on a rejected call.
- `PUT /api/exchanges/{code}/mode` with `{ "mode": "CLAIM" }` (host only, `X-Host-Token`) switches in either direction at any time; existing books and claims are untouched. `403` without the host token, `404` unknown exchange, `400` for a missing or unknown mode. Switching to the mode it is already in does nothing.
- Reading the pile works in both modes. A `CLOSED` exchange (the separate `status`) accepts neither submissions nor claims, whatever its mode.
- **No race:** submitting and claiming take a shared row lock on the exchange, and switching the mode takes an exclusive one, so a switch and a submit/claim that happen at the same moment happen one after the other: a book can never be saved after claiming began.
- A change publishes an `ExchangeModeChangedEvent` (for live updates, #15). The mode is part of the exchange details (`mode`). A later "party" mode widens the allowed values with a new changeset.

### The host, and changing an exchange's settings
`GET /api/exchanges?query=&status=&page=&size=`
- Lists only exchanges that are public (`listed`). Private exchanges never appear, even when searched for by exact name; they are reached by code with `GET /api/exchanges/{code}`.
- `query` (optional, at most 100 characters): case-insensitive "name contains" search. Surrounding spaces are ignored, and `%` and `_` are searched literally.
- `status` (optional): `OPEN` or `CLOSED`; leave it out for both. **Open exchanges come first**, then closed ones; within each, newest first (ties broken by id, so pages never repeat or skip an exchange).
- `page` (from 0, default 0) and `size` (1 to 50, default 12). Invalid values return `400`.
- Response: `{ items, page, size, totalItems, totalPages }`. Each item: `code`, `name`, `description`, `status`, `bookCount`, `claimedCount`, `createdAt`. Counts only; nothing about any book or the organizer.

### Submit a book
`POST /api/exchanges/{exchangeCode}/books`
```json
{ "submitterName": "Zach", "bookTitle": "The Hobbit",
  "amazonUrl": "https://www.amazon.com/dp/0547928221", "wrappingPaper": "candy-cane" }
```
- `201 Created` with a `Location` header and the submitted book (`claimed: false`).
- `400` for validation problems (all reported together), `404` unknown exchange, `409` if the exchange is closed or the same person (name, case-insensitive, ignoring surrounding spaces) already submitted a book to it. One book per person per exchange is enforced by a unique index; the same name can submit to a different exchange.
- Wrapping paper keys: `candy-cane`, `snowflake`, `holly`, `stars`, `plaid`, `polka-dot`, `sugar-cookie`, `christmas-tree`, `fireplace`, `sleigh`.
- A cover lookup is triggered after the book is saved, off the request thread; its failure never affects the response. The real lookup is a separate issue.

### List / get books
- `GET /api/exchanges/{exchangeCode}/books` (oldest first) and `GET /api/exchanges/{exchangeCode}/books/{bookId}`.
- The viewer is the optional `X-Participant-Name` header (name only; no header = anonymous).
- What is visible is decided on the server; hidden fields are omitted from the JSON:

| Field | Unclaimed | Claimed, other viewer | Claimed, the claimer |
|---|---|---|---|
| `id`, `submitterName`, `wrappingPaper`, `claimed`, `viewerIsClaimer` | yes | yes | yes |
| `claimedByName`, `claimedAt`, `bookTitle`, `coverImageUrl` | no | yes | yes |
| `amazonUrl` | no | **never** | yes |

- Claimer matching is case-insensitive. `404` for an unknown exchange, or a book that doesn't exist in that exchange.

### Claim a book
`POST /api/exchanges/{exchangeCode}/books/{bookId}/claim` with `{ "participantName": "Alex" }`
- `200` with the book in the **claimer's view** (title, cover, claimer, claim time and the Amazon link).
- `400` blank/missing name, `404` unknown exchange or book.
- `409` (nothing changes) if: the book is already claimed; the person already claimed a book in this exchange; the person submitted the book themselves; or the exchange is closed. Names are compared case-insensitively.
- Race-safe: an atomic `UPDATE ... WHERE claimed_by_name IS NULL` decides who gets a contested book, and the partial unique index from #1 stops one person from winning two books. Losers get `409`.
- A `BookClaimedEvent` (public view only: title, cover, claimer; never the Amazon link) is published for live updates (#15).

### API documentation (OpenAPI / Swagger)
- Swagger UI: http://localhost:8080/swagger-ui.html (try requests against your local backend)
- Spec: http://localhost:8080/v3/api-docs (JSON) or `/v3/api-docs.yaml`
- Turn both off with `SPRINGDOC_ENABLED=false` (the deployed default is decided in the deployment issue).
- **Every new endpoint documents itself** with springdoc annotations on the controller and DTOs: `@Operation` summary and description, an `@ApiResponse` for each status (error responses use the shared `ApiError` schema), and `@Schema` descriptions/examples on request and response fields. Bean Validation constraints show up automatically; `OpenApiDocsTest` checks the spec is valid.
- Careful: documentation must never reveal hidden data. Unclaimed books' title/cover/claimer and the Amazon link (claimer only) must not appear in examples of responses to other viewers.

## Test it
```bash
./mvnw verify
```
Integration tests start a throwaway Postgres with Testcontainers, so Docker must be running.

## Changing the database
The schema changes **only** through Liquibase.

1. Add a new file in `src/main/resources/db/changelog/changes/`, numbered after the last one (for example `005-add-something.sql`), written as a formatted-SQL changeset with a unique id and a `--rollback`:
   ```sql
   --liquibase formatted sql

   --changeset bookexchange:005-add-something
   ALTER TABLE exchange ADD COLUMN something VARCHAR(50);
   --rollback ALTER TABLE exchange DROP COLUMN something;
   ```
2. Include it at the end of `db/changelog/db.changelog-master.yaml`.
3. Update the JPA entity, then run `./mvnw verify`.

Never edit a changeset that has been merged; Liquibase checksums them and startup will fail. Add a new changeset instead.

## Errors
All errors are JSON: `{ "error": "...", "details": [ ... ] }`. Unexpected errors return a generic message; details go to the log only.
