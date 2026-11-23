# 01 – Database schema script

## Story
As a developer, I want a Postgres SQL script that creates the exchange and book submission tables so that the backend has a place to persist data.

## Details
- Plain SQL script at `db/init.sql` (migration tooling such as Flyway can come later).
- Optionally a `docker-compose.yml` that starts Postgres and runs the script on first boot.

### `exchange`
| column | type | notes |
|---|---|---|
| id | bigint / uuid PK | |
| code | varchar(12) not null **unique** | short shareable code used in URLs and API paths, e.g. `K7M2QX` (see story 12) |
| name | varchar(200) not null | e.g. "Office Christmas 2026" |
| description | varchar(1000) null | |
| created_by_name | varchar(100) null | organizer's name as typed |
| listed | boolean not null default true | `true` = shows up when browsing exchanges; `false` = join by code/link only (story 13) |
| status | varchar(20) not null default `OPEN` | `OPEN` / `CLOSED` |
| created_at | timestamptz not null default now() | |

### `book_submission`
| column | type | notes |
|---|---|---|
| id | bigint / uuid PK | |
| exchange_id | FK → exchange.id, not null | |
| submitter_name | varchar(100) not null | |
| book_title | varchar(300) not null | |
| amazon_url | varchar(2048) not null | |
| asin | varchar(20) null | Amazon product id parsed from the link, used for cover lookup (story 14) |
| cover_image_url | varchar(2048) null | resolved cover image; null = none found, UI shows a placeholder |
| wrapping_paper | varchar(50) not null | key identifying the paper design (see story 10) |
| claimed_by_name | varchar(100) null | null = unclaimed |
| claimed_at | timestamptz null | |
| created_at | timestamptz not null default now() | |

### Constraints / indexes
- Index on `book_submission(exchange_id)`.
- **Partial unique index** on `(exchange_id, lower(claimed_by_name)) WHERE claimed_by_name IS NOT NULL` so the database itself guarantees a person can claim only one book per exchange.
- Check constraint: `claimed_by_name` and `claimed_at` are both null or both set.
- Seed one open exchange (with a fixed code like `XMAS2026`) so the app is usable immediately.

## Acceptance criteria
- [ ] Running the script on an empty database creates both tables without errors.
- [ ] Running it twice is safe (`IF NOT EXISTS`) or documented as one-shot.
- [ ] Attempting to set the same `claimed_by_name` on two books in one exchange fails at the DB level.
- [ ] A seeded exchange exists.
