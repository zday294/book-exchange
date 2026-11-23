# 03 – Submit a book endpoint

## Story
As a participant, I want to submit a book (my name, the book's name, an Amazon link, and a wrapping paper choice) so that it is added to the exchange pile.

## Endpoint
`POST /api/exchanges/{exchangeCode}/books`

Request:
```json
{
  "submitterName": "Zach",
  "bookTitle": "The Hobbit",
  "amazonUrl": "https://www.amazon.com/dp/0547928221",
  "wrappingPaper": "candy-cane"
}
```

Response `201 Created` with `Location` header. The response goes to the submitter, so it may echo back what they entered (including title and link) with `claimed: false`. It is **not** the shape used by the public pile (see story 04).

## Validation
- All fields required; names/titles trimmed and length-limited (match DB columns).
- `amazonUrl` must be a valid `https` URL whose host is `amazon.com`, a subdomain, or `amzn.to` / `a.co` (short links). Make the allowed-host list configurable.
- `wrappingPaper` must be one of the known keys (see story 10).
- Exchange must exist and be `OPEN`; otherwise `404` / `409`.
- Validation failures return `400` in the standard error format.

## Cover image
After the book is saved, the backend looks up its cover image (story 14). This must **never** make the submission fail or noticeably slow it down: the book is saved first, the cover is resolved right after (async), and a missing cover is acceptable.

## Acceptance criteria
- [ ] Valid request persists a `book_submission` with `claimed_by_name = null`.
- [ ] Each validation rule above has a test.
- [ ] Submitting to a closed or missing exchange is rejected.
- [ ] A cover-lookup failure still results in a `201`.
- [ ] Unit/integration tests (MockMvc + Testcontainers Postgres or H2 in PG mode).

## Open questions
- Should one person be allowed to submit more than one book? (Default: yes, no limit.)
