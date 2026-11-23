# 17 – Optional sign-in and history  *(future)*

## Story
As a regular participant, I want to optionally sign in so I can see a history of the books I've submitted and the books I've claimed across exchanges.

## Principles
- **Optional:** the name-only flow (story 08) keeps working. Signing in is an upgrade, not a gate.
- Signed-in identity replaces the typed name for that user, which also closes the impersonation gap noted in story 04.

## Rough scope (to refine before building)
- Choose an auth approach: social/OIDC login (Google, etc.) via Spring Security OAuth2 client is the least effort and avoids storing passwords; alternatives are email magic-link or username/password.
- New `app_user` table (id, display name, email, provider id). Add nullable `user_id` to `book_submission` (submitter) and the claimer, alongside the existing name columns, so anonymous rows stay valid.
- "Claim my past activity": when a signed-in user's name matches anonymous entries, offer to attach them (needs care; may be skipped).
- Pages: "My books" (submitted + claimed, grouped by exchange) and "My exchanges".
- Unlocks organizer features deferred in story 12 (edit/close an exchange) and makes the claimer-only Amazon link truly protected.
- The one-claim-per-exchange rule must work for both anonymous and signed-in claimers (e.g. unique on `user_id` when set, on name otherwise).

## Acceptance criteria (draft)
- [ ] A user can sign in and sign out; anonymous use still works.
- [ ] Signed-in submissions and claims are linked to the user.
- [ ] "My books" lists everything that user submitted or claimed, across exchanges.
- [ ] A signed-in claimer's Amazon link cannot be seen by someone merely typing their name.
