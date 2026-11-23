# 08 – Participant name entry and persistence

## Story
As a participant, I want to enter my name once when I arrive and have the app remember it, so that I don't have to retype it and the app knows who is claiming books.

## Details
- There is no real login; the "logged in" user is just a name.
- When a user opens the book-exchange page (or submit page) without a saved name, show a modal/gate asking for their name before showing anything else.
- Persist the name in `localStorage` (preferred over a cookie since the backend doesn't need to read it automatically; it is sent explicitly via `X-Participant-Name`). Wrap in a Pinia store (`useParticipantStore`).
- Trim input; require 1–100 characters.
- Provide a "Not you? Change name" control in the header.
- Name comparisons on the backend are case-insensitive, so "alex" and "Alex" are the same person. Store/display the name as the user typed it.

## Future
An optional sign-in (story 17) will later let a participant have a persistent identity and history. Keep identity behind the `useParticipantStore` so signed-in users can slot in without touching pages.

## Caveat to be aware of
Anyone can type any name, so this is trust-based, not security. That is acceptable for a friendly office/family exchange; real auth is out of scope.

## Acceptance criteria
- [ ] First visit shows the name prompt; the rest of the page is inaccessible until a name is provided.
- [ ] After entering a name and refreshing, the prompt does not reappear.
- [ ] Changing the name updates the header and subsequent API calls.
- [ ] Empty/whitespace-only names are rejected.
