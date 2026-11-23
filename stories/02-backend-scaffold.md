# 02 – Spring Boot backend scaffold

## Story
As a developer, I want a runnable Spring Boot project connected to Postgres so that API stories have a foundation to build on.

## Details
- Directory: `backend/`. Java 21, Spring Boot 3.x, Maven or Gradle (pick one).
- Dependencies: Spring Web, Spring Data JPA, Validation, PostgreSQL driver, Lombok (optional), Spring Boot Test.
- JPA entities `Exchange` and `BookSubmission` mapped to the tables from story 01, with Spring Data repositories.
- `ddl-auto=validate` so the SQL script remains the source of truth.
- Connection settings via `application.yml` with environment-variable overrides.
- CORS configured to allow the Vue dev server origin (e.g. `http://localhost:5173`).
- Consistent JSON error format (`{ "error": "...", "details": [...] }`) via `@RestControllerAdvice`.
- Health check at `/actuator/health`.

## Acceptance criteria
- [ ] `./mvnw spring-boot:run` (or Gradle equivalent) starts against the local Postgres.
- [ ] Entities validate against the schema on startup.
- [ ] Health endpoint returns `UP`.
- [ ] README in `backend/` documents how to run it.
