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
| `BOOK_LINK_ALLOWED_HOSTS` | `amazon.com,amzn.to,a.co,barnesandnoble.com,bn.com` | Hosts a submitted book link may use: Amazon (`amazon.com`, `amzn.to`, `a.co`) or Barnes & Noble (`barnesandnoble.com`, `bn.com`); subdomains such as `www.amazon.com` are allowed too, lookalikes are not. Replaces `AMAZON_ALLOWED_HOSTS` |
| `LINK_CHECK_ENABLED` | `true` | Opens each submitted book link to check the page exists (see "Link check"). Set `false` without internet or in tests |
| `LINK_CHECK_CONNECT_TIMEOUT_MS` / `LINK_CHECK_TOTAL_TIMEOUT_MS` / `LINK_CHECK_MAX_REDIRECTS` | `2000` / `4000` / `5` | Limits for that check |
| `LINK_CHECK_CACHE_MINUTES` | `10` | How long a definite answer (page exists / not found) is remembered per exact link; `0` turns it off |
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

### Party mode (a queue of turns)
The host can start a **party**: everyone gets a turn to claim a present, one person at a time.
- `POST /api/exchanges/{code}/party/start` (host only) switches the exchange to `PARTY` mode (from submit or claim mode) and queues everyone who is **online** and hasn't claimed a book, in a **random** order; the first person's turn starts at once. `409` if a party is already running, the exchange is closed, there are no presents left, or nobody is online. `POST .../party/skip` moves the current person to the end of the line; `POST .../party/end` ends the party and returns the exchange to claim mode (claims stay). `GET /api/exchanges/{code}/party` shows the queue to anyone: `{active, endedReason?, current?, turnSecondsLeft?, queue:[{name, status, online}], eligible}` (names and order only; `DONE` entries first, then the current person, then those waiting). The mode can't be switched to or from `PARTY` with the plain mode switch.
- **Turns:** each lasts `PARTY_TURN_SECONDS` (default 120). In party mode only the current person can claim; anyone else gets `409` ("It's not your turn; Alex is up"). A successful claim by the current person marks them done and starts the next turn in the same transaction. If the time runs out, the person goes to the **end of the line** (they are not removed) and the next turn starts. The same happens when the host skips them or they go offline.
- **The server is the clock:** turn end times are stored in the database and compared with the database's `now()`, so a claim that arrives after a turn ended is refused even if the timer hasn't run yet, a restart neither loses nor extends a turn, and a wrong clock on someone's device only affects their own countdown. A timer runs about once a second (`PARTY_TICK_MILLIS`) for each running party.
- **Online** means having the exchange page open with a saved name: the live stream is opened as `GET /api/exchanges/{code}/events?name=Alex` (a browser's `EventSource` can't send headers). A name with a connection open is online; after the last one closes it stays online for `PARTY_OFFLINE_GRACE_SECONDS` (default 30), so a page refresh or a moment without signal doesn't lose a turn. Someone who is offline when their turn comes, or goes offline during it, is passed over after that grace period. People who arrive after the party started (and haven't claimed) join the end of the line.
- **A party also ends by itself** when every present is claimed ("All the presents have been claimed. Party over!"), or when a whole round of turns passes without a single claim ("Nobody claimed a present for a whole round..."). If everyone in line has had their turn but presents remain, it stays open for late arrivals until the host ends it.
- **No races:** claiming, the timer, skipping and ending all take the exchange's row lock (`FOR UPDATE`), so they happen one after the other; the turn is completed with a conditional update that only one claim can win. Changes are announced on the live stream as `party-changed` snapshots (after commit).
- **Single instance:** who is online is kept in the memory of one server process (like the live streams), so a party needs a single backend instance for now. See the deployment note under Live updates.

### Live updates
`GET /api/exchanges/{code}/events?name=` (the optional `name` says who is watching, for the party) is a long-lived **Server-Sent Events** stream (`text/event-stream`) for one exchange; browsers open it with `EventSource`. `404` for an unknown exchange, `503` when the server already holds its maximum number of connections.
- `ready`: the connection is open (sent at once).
- `book-added`: `{id, submitterName, wrappingPaper}`, a new wrapped present. Nothing about the book.
- `book-shaped`: `{id, coverAspectRatio}`, the cover lookup finished, so the present's proportions are now known. Nothing else about the cover.
- `book-claimed`: `{id, claimedByName, claimedAt, bookTitle, coverImageUrl?}`. Public once a book is claimed. **Never the book link**; that stays in the claimer's own response.
- `mode-changed`: `{mode}` (`SUBMIT`, `CLAIM` or `PARTY`).
- `party-changed`: the whole party queue (see Party mode). Names and order only.
- A comment line (`: keep-alive`) every 15 seconds keeps proxies from closing an idle connection and lets the server notice a browser that went away.
- Events are sent only **after the database transaction commits**, so a rejected or rolled-back request announces nothing. Events of one exchange never reach watchers of another. Events sent while a browser was disconnected are **not replayed**: the front end re-reads the pile when it reconnects.
- Connections last at most 30 minutes (the browser reconnects by itself), and all are closed as soon as the application starts shutting down, so a deploy is not held up by open streams.

Settings (environment variables): `PARTY_TURN_SECONDS` (120), `PARTY_OFFLINE_GRACE_SECONDS` (30), `PARTY_TICK_MILLIS` (1000), `EVENTS_HEARTBEAT_MILLIS` (15000), `EVENTS_MAX_SUBSCRIBERS` (1000, roughly one per open browser page), `EVENTS_CONNECTION_MINUTES` (30).

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
  "bookUrl": "https://www.amazon.com/dp/054792822X", "wrappingPaper": "candy-cane" }
```
- `201 Created` with a `Location` header and the submitted book (`claimed: false`).
- `400` for validation problems (all reported together), `404` unknown exchange, `409` if the exchange is closed or the same person (name, case-insensitive, ignoring surrounding spaces) already submitted a book to it. One book per person per exchange is enforced by a unique index; the same name can submit to a different exchange.
- Wrapping paper keys: `candy-cane`, `snowflake`, `holly`, `stars`, `plaid`, `polka-dot`, `sugar-cookie`, `christmas-tree`, `fireplace`, `sleigh`.
- The link is checked before saving (see "Link check"): a page the store says doesn't exist gives `400` with `details: ["bookUrl: That link doesn't seem to work (the page wasn't found). Check it and try again."]` and nothing is saved.
- A cover lookup is triggered after the book is saved, off the request thread; its failure never affects the response (see "Cover lookup" below).

### List / get books
- `GET /api/exchanges/{exchangeCode}/books` (oldest first) and `GET /api/exchanges/{exchangeCode}/books/{bookId}`.
- The viewer is the optional `X-Participant-Name` header (name only; no header = anonymous).
- What is visible is decided on the server; hidden fields are omitted from the JSON:

| Field | Unclaimed | Claimed, other viewer | Claimed, the claimer |
|---|---|---|---|
| `id`, `submitterName`, `wrappingPaper`, `claimed`, `viewerIsClaimer` | yes | yes | yes |
| `coverAspectRatio` (cover width / height, two decimals, e.g. `0.67`; omitted until a cover is found) | **yes** | yes | yes |
| `claimedByName`, `claimedAt`, `bookTitle`, `coverImageUrl` | no | yes | yes |
| `bookUrl` | no | **never** | yes |

- **A deliberate exception to "an unclaimed book reveals nothing":** `coverAspectRatio` is sent for unclaimed books so presents can be drawn in the book's real proportions. It is one rounded number: never the image URL, the link, the title or pixel sizes (the cover's size could identify an edition). It does hint at the kind of book (paperback, hardcover, picture book); that is accepted. Tests assert that an unclaimed book exposes exactly the ratio and nothing else about the cover.
- Claimer matching is case-insensitive. `404` for an unknown exchange, or a book that doesn't exist in that exchange.

### Claim a book
`POST /api/exchanges/{exchangeCode}/books/{bookId}/claim` with `{ "participantName": "Alex" }`
- `200` with the book in the **claimer's view** (title, cover, claimer, claim time and the book link).
- `400` blank/missing name, `404` unknown exchange or book.
- `409` (nothing changes) if: the book is already claimed; the person already claimed a book in this exchange; the person submitted the book themselves; or the exchange is closed. Names are compared case-insensitively.
- Race-safe: an atomic `UPDATE ... WHERE claimed_by_name IS NULL` decides who gets a contested book, and the partial unique index from #1 stops one person from winning two books. Losers get `409`.
- A `BookClaimedEvent` (public view only: title, cover, claimer; never the book link) is published for live updates (#15).

### API documentation (OpenAPI / Swagger)
- Swagger UI: http://localhost:8080/swagger-ui.html (try requests against your local backend)
- Spec: http://localhost:8080/v3/api-docs (JSON) or `/v3/api-docs.yaml`
- Turn both off with `SPRINGDOC_ENABLED=false` (the deployed default is decided in the deployment issue).
- **Every new endpoint documents itself** with springdoc annotations on the controller and DTOs: `@Operation` summary and description, an `@ApiResponse` for each status (error responses use the shared `ApiError` schema), and `@Schema` descriptions/examples on request and response fields. Bean Validation constraints show up automatically; `OpenApiDocsTest` checks the spec is valid.
- Careful: documentation must never reveal hidden data. Unclaimed books' title/cover/claimer and the book link (claimer only) must not appear in examples of responses to other viewers.

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

## Cover lookup
After a book is saved, `IsbnCoverLookup` runs in the background (`@Async`, after the commit) and fills `book_submission.book_identifier` and `cover_image_url`:

1. The book's identifier is read from the link, by store:
   - **Amazon:** the ASIN (`/dp/`, `/gp/product/`, `/gp/aw/d/`, `/gp/offer-listing/`, `/exec/obidos/ASIN/`, `/product/`). For a short link (`a.co`, `amzn.to`) the redirects are followed until a URL with an ASIN appears; the Amazon product page itself is never downloaded.
   - **Barnes & Noble** (`barnesandnoble.com`, `bn.com`): the `ean` query parameter (an ISBN-13), as in `.../w/the-hobbit/1100054405?ean=9780547928227`. It must be a valid ISBN-13 (978/979 prefix, checksum checked). Barnes & Noble links are never followed: a link without a usable `ean` (a short `bn.com` form, no query string, a mistyped number) is saved as it is and gets no cover, so the page shows the title placeholder.
2. If the identifier is a valid ISBN-10 (Amazon print books) or ISBN-13 (Barnes & Noble), the cover comes from Open Library (`https://covers.openlibrary.org/b/isbn/<isbn>-L.jpg`, which accepts both kinds). The lookup only stores the URL after confirming the image really exists. The browser loads the image from Open Library.
3. Otherwise (a non-book Amazon product such as `B08N5WRWNW`, no cover at the source, or any failure) `cover_image_url` stays empty and the page shows a placeholder with the title. Amazon's `og:image` is deliberately not used: it is blocked often and may breach their terms.

The image's width and height are read from its header and only their ratio is kept (`cover_aspect_ratio`, rounded to two decimals, 0.10 to 10.00; an unreadable image just gets no ratio). When it is known a `book-shaped` live event goes out so open pages redraw the present. Added by migration `009-cover-aspect-ratio.sql`.

`book_identifier` holds what was recognised: the Amazon ASIN (an ISBN-10 for print books, or an id like `B08N5WRWNW`) or the Barnes & Noble ISBN-13. It was called `asin` until migration `011-rename-asin-to-book-identifier.sql`, which only renames the column. It is never returned by the API. Books submitted before B&N covers existed are not backfilled. To fetch real covers for both kinds on demand (needs internet; not part of the normal run): `mvn test -Dtest=LiveCoverFetchTest -Dlive.links=true`.

### Link check
When a book is submitted the server opens its link (`GuardedLinkChecker`) to see whether the page exists. **Only a definite "not found" (404 or 410) rejects the submission.** Everything else (success, 403, 429, 5xx, timeout, connection error, a redirect somewhere not allowed) leaves the link "unverified" and the book is accepted, because stores often block programs.
- Order: the cheap checks first (exchange exists, open, accepting books, person hasn't submitted), then the link check, then the save. The check runs between two short transactions, so no transaction or lock is held while waiting for a store, and refused requests never cause a fetch.
- `HEAD` is tried first; if the store refuses it or says 404, a `GET` confirms before the link is called dead (only the status and headers are read, never the page). Redirects are followed by hand (at most 5).
- Safety, since the server fetches an address a person typed (the same rules as the cover lookup, in `net.PinnedFetcher`): every address visited (the link and each redirect) must be https, on the default port, without credentials and on an allowed host (`BOOK_LINK_ALLOWED_HOSTS`); the host must resolve only to public addresses (never loopback, private, link-local, metadata, carrier-grade NAT or IPv6 local ranges); the connection goes to the address that was checked (the TLS certificate is still verified against the host name), so a second DNS answer can't redirect it; connect timeout 2 s and 4 s overall; no cookies, no credentials, nothing about the participant, and an honest `User-Agent`.
- Remembered answers: a definite answer (`OK` or `NOT_FOUND`) is kept for `LINK_CHECK_CACHE_MINUTES` (default 10) per exact link, up to 500 links, in memory (one backend instance), so fixing a typo or two people bringing the same book doesn't open the page again. "Couldn't tell" is never remembered, since it is usually a temporary block.
- **Watching for a store that starts blocking us:** every check logs one line, `Link check: host=<host> result=<OK|NOT_FOUND|UNVERIFIED> status=<code> ...`. A sudden run of `NOT_FOUND` for good links (or `UNVERIFIED` for everything) means a store changed how it treats programs: set `LINK_CHECK_ENABLED=false` (no code change) and investigate. Stores treat unusual request headers as robot traffic (Amazon answers `200` to everything if the request carries `Accept-Encoding: identity`), so the client sends only `Host`, `User-Agent`, `Accept` and `Connection`. To check the real stores on demand (needs internet, never part of the normal test run): `mvn test -Dtest=LiveLinkCheckTest -Dlive.links=true`. It fails if a known-good link is called dead or a known-dead one is no longer caught.
- Deliberately not done: links are checked only when submitted, never again (if a link dies later, the claimer and the submitter sort it out between them). Spotting "page not found" pages that answer 200 is a later ticket (#87).
- What the real stores return (checked on 2026-11-25): Amazon answers `HEAD` with `405` (so the `GET` fallback is essential) and a missing product with a real `404`; Barnes & Noble answers a missing product with `404` for both; `a.co` short links that don't exist give `404`. Note that Amazon links with a bad ISBN-10 check digit are genuine 404s, which is why the examples here use `054792822X`.

### Book links: Amazon and Barnes & Noble
The submitted link (`bookUrl`, column `book_url`) must be `https`, with no credentials and the default port, on `amazon.com`, `amzn.to`, `a.co`, `barnesandnoble.com` or `bn.com` (or a subdomain such as `www.barnesandnoble.com`). The list is the `BOOK_LINK_ALLOWED_HOSTS` setting. The field was called `amazonUrl` (column `amazon_url`, setting `AMAZON_ALLOWED_HOSTS`) before; migration `010-rename-amazon-url-to-book-url.sql` renames the column and keeps existing links. Which store a link goes to is worked out by the front end from its host ("View on Barnes & Noble"); the API does not return it. The privacy rule is unchanged: only the claimer ever receives the link.

A failed lookup never affects the submission, and it records nothing else about the book. The cover is only revealed on claim (see the visibility table above).

**Safety rules**, because the server fetches addresses people typed in (`GuardedCoverHttp`, on `net.PinnedFetcher`): only allow-listed hosts (Amazon hosts for short links, `covers.openlibrary.org` and `archive.org` for images), checked again on every redirect (at most 3, followed by hand); https on port 443 only; no credentials in the address; a host that resolves to a loopback, private, link-local, carrier-grade-NAT or unique-local address is refused; an 8 second limit per step including the download; a 2 MB size cap; the response must be an `image/*` type. The cover lookup and the link check share one fetcher (`net.PinnedFetcher` with a small `SocketTransport`): the connection goes to the address that was checked (the TLS certificate is still verified against the host name), so DNS changing between the check and the connection can't redirect it. A body is read only for a `200` answer of the right type, up to the size cap, and never when the answer is compressed.

| Setting | Environment variable | Default |
|---|---|---|
| `app.cover.enabled` | `COVER_LOOKUP_ENABLED` | `true` (`false`: books never get covers, nothing is fetched) |
| `app.cover.image-url-template` | `COVER_IMAGE_URL_TEMPLATE` | Open Library (`%s` is the ISBN-10) |
| `app.cover.allowed-image-hosts` | `COVER_ALLOWED_IMAGE_HOSTS` | `covers.openlibrary.org,archive.org` |

Tests never reach the network: the other integration tests switch the lookup off, and the cover tests stub the HTTP layer (`CoverHttp`). Not done yet: a way to re-run the lookup for books that have no cover (a maintenance task), and storing the images ourselves instead of hot-linking.

## Tests and the internet
No test reaches the internet. `src/test/resources/application.properties` turns the link check and the cover lookup **off for every Spring test**; a test that wants them on says so (`@TestPropertySource` or `@SpringBootTest(properties = ...)`) and fakes `LinkChecker` / `CoverHttp` (see `LinkCheckTest`, `CoverLookupTest`). `NoInternetByDefaultTest` guards this. The only test that talks to the real stores is `LiveLinkCheckTest`, which is skipped unless run with `-Dlive.links=true`.
