# 11 – Claim a book from the pile (UI)

## Story
As a participant, I want to click a present in the pile to claim it, and be stopped from claiming more than one, so that I get exactly one book.

## Depends on
05, 08, 09.

## Details
- Clicking an unclaimed present opens a confirmation ("Claim this present from Zach?") — claiming is not undoable, so confirm first.
- On confirm, call the claim endpoint with the saved participant name.
- **Success:** present flips to claimed state and a reveal dialog shows the cover, title and the **Amazon link** (claimer only). Uses the unwrap animation once story 16 exists.
- **Already claimed by me:** if the participant already holds a book, unclaimed presents are not clickable (disabled styling + tooltip "You've already claimed a book"), and the header shows their book with its link.
- **Own submission:** presents the participant submitted themselves are not claimable (tooltip "You brought this one").
- **Conflicts:** if the server returns `409` (someone else got it first, or you already claimed), show a friendly message; the pile refreshes to the true state.
- The UI state is a convenience; the backend is the source of truth for the one-book rule.

## Acceptance criteria
- [ ] Claiming an available present marks it claimed by the current name and reveals cover, title and link.
- [ ] After claiming, every other present is disabled for this participant.
- [ ] The participant's own submissions cannot be claimed.
- [ ] A race loss (409) is handled gracefully and the pile refreshes.
- [ ] Reloading the page still shows the participant's claimed book and link (derived from server data, not just local state).
