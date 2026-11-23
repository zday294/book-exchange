# 13 – Find book exchanges

## Story
As a participant, I want to find an exchange to join, either by browsing or by entering a code, so that I can take part.

## Backend
- `GET /api/exchanges?query=&page=&size=` – lists exchanges where `listed = true`, newest first, with optional name search (case-insensitive contains). Each item: `code`, `name`, `description`, `status`, `bookCount`, `claimedCount`, `createdAt`.
- Unlisted exchanges never appear in this list or in search results; they are reachable only via `GET /api/exchanges/{exchangeCode}` (story 12).

## Front end
- Route `/` (home) shows:
  - A "Have a code?" box: enter a code → goes to `/exchange/<code>` (shows a friendly "not found" if unknown).
  - A "Create an exchange" button (story 12).
  - A searchable list of public exchanges as cards (name, description, how many books, open/closed badge); clicking opens the exchange page.
- Empty state when there are none yet.
- Remember the last visited exchange code in `localStorage` and offer a "Continue to <name>" shortcut.

## Acceptance criteria
- [ ] Public exchanges are listed and searchable; private ones never appear.
- [ ] Entering a valid code navigates to the exchange; an invalid one shows an error.
- [ ] Code entry is case-insensitive and trims whitespace.
- [ ] List is paginated on the API.

## Future
- With sign-in (story 17): "my exchanges" (ones I created or joined).
