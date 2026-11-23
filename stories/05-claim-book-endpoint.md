# 05 – Claim a book endpoint

## Story
As a participant, I want to claim a book from the pile, and be prevented from claiming a second one, so that the exchange stays fair.

## Endpoint
`POST /api/exchanges/{exchangeCode}/books/{bookId}/claim`

Request:
```json
{ "participantName": "Alex" }
```

Response `200 OK` with the book in the **claimer's view** from story 04: title, cover, `claimedByName`, `claimedAt` **and** `amazonUrl`.

## Rules
- Book must exist in the exchange and be unclaimed → otherwise `409 Conflict` ("already claimed").
- The participant must not already have claimed a book in this exchange (case-insensitive name match) → `409 Conflict` ("you already claimed a book").
- A participant cannot claim a book they submitted themselves (case-insensitive) → `409` / `422`.
- Exchange must be `OPEN`.
- Must be race-safe: two simultaneous claims on the same book, or two claims by the same person, result in exactly one success. Use a conditional update (`UPDATE ... WHERE claimed_by_name IS NULL`) and/or optimistic locking, and rely on the partial unique index from story 01 as the backstop (translate the violation into a `409`).
- After a successful claim, publish a "book claimed" event for live updates (story 15). That event contains the **public** claimed view only: title, cover, claimer name; **never** the Amazon link.

## Acceptance criteria
- [ ] Successful claim sets `claimed_by_name` and `claimed_at`, and returns the claimer's view including the Amazon link.
- [ ] Each rule above returns the right status and message.
- [ ] Concurrency test: parallel claims on one book yield a single `200`.
- [ ] Concurrency test: parallel claims by one person on different books yield a single `200`.
- [ ] The published event contains no Amazon link.

## Out of scope
- "Unclaim"/swap feature.
