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
### Submit a book
`POST /api/exchanges/{exchangeCode}/books`
```json
{ "submitterName": "Zach", "bookTitle": "The Hobbit",
  "amazonUrl": "https://www.amazon.com/dp/0547928221", "wrappingPaper": "candy-cane" }
```
- `201 Created` with a `Location` header and the submitted book (`claimed: false`).
- `400` for validation problems (all reported together), `404` unknown exchange, `409` if the exchange is closed or the same person (name, case-insensitive, ignoring surrounding spaces) already submitted a book to it. One book per person per exchange is enforced by a unique index; the same name can submit to a different exchange.
- Wrapping paper keys: `candy-cane`, `snowflake`, `holly`, `stars`, `plaid`, `polka-dot`.
- A cover lookup is triggered after the book is saved, off the request thread; its failure never affects the response. The real lookup is a separate issue.

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
