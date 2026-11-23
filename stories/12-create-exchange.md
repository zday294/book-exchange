# 12 – Create a book exchange

## Story
As an organizer, I want to create a new book exchange so that my group can run its own.

## Backend
`POST /api/exchanges`
```json
{ "name": "Office Christmas 2026", "description": "Bring a book under $20", "organizerName": "Zach", "listed": true }
```
- Generates a unique, short, unambiguous `code` (e.g. 6–8 chars, no `0/O/1/I`), retrying on collision.
- Creates the exchange as `OPEN`. Returns `201` with the exchange including its `code`.
- Validation: `name` required (≤200), `description` optional (≤1000), `organizerName` required.
- `GET /api/exchanges/{exchangeCode}` returns an exchange's details (name, description, status, counts of books/claimed). Unknown code → `404`.

## Front end
- Route: `/exchanges/new` with a form for the fields above and a "Show in the public list" checkbox (maps to `listed`; unchecked = private, join by link only).
- On success, show the shareable link (`/exchange/<code>`) with a copy button and the code itself, then link into the exchange page.
- The creator's name prefills from the saved participant name (story 08).

## Acceptance criteria
- [ ] Creating an exchange returns a unique code and the exchange is immediately usable (books can be submitted).
- [ ] Validation errors are reported per field.
- [ ] Shareable link can be copied and opens the exchange page.
- [ ] Code generation collision is handled (test with a forced collision).

## Not included yet
- Closing/reopening an exchange, editing it, or organizer-only permissions. Without sign-in there is no way to prove who the organizer is, so these wait for story 17. (`status` exists in the schema for when we get there.)
