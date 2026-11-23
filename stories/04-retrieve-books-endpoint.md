# 04 – Retrieve books endpoints

## Story
As the front end, I want to retrieve submitted books so that I can render the pile, show what others have claimed, and show a user their own claimed book.

## Endpoints
- `GET /api/exchanges/{exchangeCode}/books` – all books in the exchange (for the pile).
- `GET /api/exchanges/{exchangeCode}/books/{bookId}` – a single book.

The requester is identified by the `X-Participant-Name` header (name-only identity, see story 08). The header is optional; without it the requester is treated as an anonymous viewer.

## Visibility rules (the surprise is the point)

| Field | Unclaimed | Claimed – other viewers | Claimed – the claimer |
|---|---|---|---|
| `id`, `submitterName`, `wrappingPaper`, `claimed` | ✅ | ✅ | ✅ |
| `bookTitle` | ❌ | ✅ | ✅ |
| `coverImageUrl` | ❌ | ✅ | ✅ |
| `claimedByName`, `claimedAt` | ❌ | ✅ | ✅ |
| `amazonUrl` | ❌ | ❌ **never** | ✅ |

- The Amazon link is only ever returned to the claimer. No other endpoint, event or log-facing payload may include it for anyone else (this includes the live-update events in story 15).
- Hiding is done **on the server** by building a different DTO per case. Never ship hidden fields and rely on the UI to not show them.
- The cover image is hidden while unclaimed because it would give the book away.
- Add a `viewerIsClaimer` boolean (or similar) so the UI can tell which case it is in.

## Response examples
Unclaimed:
```json
{ "id": 12, "submitterName": "Zach", "wrappingPaper": "candy-cane", "claimed": false }
```
Claimed, seen by someone else:
```json
{
  "id": 12, "submitterName": "Zach", "wrappingPaper": "candy-cane", "claimed": true,
  "claimedByName": "Alex", "claimedAt": "2026-12-01T18:02:11Z",
  "bookTitle": "The Hobbit", "coverImageUrl": "https://…/cover.jpg"
}
```
Claimed, seen by Alex: the same plus `"amazonUrl": "https://www.amazon.com/dp/0547928221"` and `"viewerIsClaimer": true`.

## Acceptance criteria
- [ ] List returns all books for an exchange in a stable order.
- [ ] Unclaimed books never leak title, cover, link or claimer in either endpoint.
- [ ] Claimed books show title, cover and claimer to everyone, but the Amazon link only to the claimer.
- [ ] Name matching for "is the claimer" is case-insensitive.
- [ ] Unknown exchange or book returns `404`.
- [ ] Tests cover every cell of the visibility table.

## Known limitation
Because identity is name-only and unauthenticated, someone could type another participant's name to see their link. Acceptable for now; the optional sign-in (story 17) closes this gap.
