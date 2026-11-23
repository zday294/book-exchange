# 09 – Book exchange page: tree and pile

## Story
As a participant, I want to see a Christmas tree with a pile of wrapped books beneath it so that the exchange feels festive and I can see what's available and what others have picked.

## Depends on
04, 06, 08, 10. Live updates come from story 15; the unwrap animation from story 16.

## Details
- Route: `/exchange/:exchangeCode`.
- **Layout:** a Christmas tree illustration at the top (inline SVG is a good fit; simple and scalable), with the pile of presents below/around its base.
- **Pile:** one present per book, rendered using that book's `wrappingPaper` (story 10). Presents are arranged loosely (grid with slight random-but-stable rotation/offset seeded by book id so layout doesn't jump on refresh).
- Each present shows the submitter's name on a gift tag ("From: Zach").
- **Unclaimed present:** wrapped; no title, no cover.
- **Claimed present (any viewer):** shown unwrapped/opened, displaying the **book cover** (placeholder if none), the **book title**, and "Claimed by Alex". Claimed books stay in the pile (or move to an "opened" area — design decision).
- **The Amazon link** appears only on the viewer's own claimed book (a "View on Amazon" button). No one else ever sees it.
- Header shows the current participant name and whether they have claimed a book yet.
- Empty state when there are no books ("No presents yet — add one!") with a link to the submit page.
- **Live:** the page updates by itself when anyone claims a book or adds one (story 15). Until that story lands, a simple periodic refresh is acceptable as a stopgap.

## Acceptance criteria
- [ ] Tree renders above the pile on desktop and mobile.
- [ ] Every submitted book appears as a present with the right paper and submitter tag.
- [ ] Unclaimed presents reveal nothing about the book.
- [ ] Claimed presents show cover, title and claimer to every viewer.
- [ ] The Amazon link is shown only to the claimer.
- [ ] Layout is stable across refreshes.
- [ ] Empty state is shown when the exchange has no books.
