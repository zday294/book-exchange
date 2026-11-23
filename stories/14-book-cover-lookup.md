# 14 – Book cover lookup

## Story
As a participant, I want each book to show its cover once it's claimed, pulled from the Amazon link that was submitted, so the reveal feels real.

## Heads-up: Amazon doesn't make this easy
Amazon has no free public API for cover images (the Product Advertising API requires an affiliate account and sales history), its pages block automated fetching and change often, and scraping them may violate Amazon's terms. So the design below treats Amazon as the source of the **book identity**, not necessarily the **image**, and degrades gracefully.

## Approach (try in order; stop at first success)
1. **Parse the ASIN** from the submitted URL (`/dp/<ASIN>`, `/gp/product/<ASIN>`). Resolve `a.co` / `amzn.to` short links by following redirects (see safety rules below). Store in `book_submission.asin`.
2. **If the ASIN is a 10-digit ISBN** (almost all print books on Amazon are), fetch the cover from a free book-cover source, e.g. Open Library (`https://covers.openlibrary.org/b/isbn/<isbn>-L.jpg`) or the Google Books API. This is the most reliable and clean option.
3. **Best effort:** fetch the Amazon page and read its `og:image` meta tag. May be blocked; do not depend on it.
4. **Fallback:** leave `cover_image_url` null; the UI shows a generic book-cover placeholder using the title.

Decision for the team: whether to include step 3 at all given the terms-of-service concern. Recommended: start with 1, 2 and 4.

## Details
- Runs asynchronously right after a book is saved (story 03); never blocks or fails the submission.
- Store the resulting URL in `cover_image_url`. Optionally download and store the image ourselves later to avoid hot-linking (not required now).
- Notify live clients? Not needed: covers are only revealed on claim, and the claim event (story 15) carries the current cover.
- A small admin/maintenance path to re-run lookup for books with a null cover is nice-to-have.

## Safety rules (the server fetches user-supplied URLs)
- Only follow URLs on the allow-listed Amazon hosts; follow at most a few redirects and re-check the host on every hop.
- Block private/loopback/link-local IP ranges (SSRF protection).
- Short connect/read timeouts (a few seconds) and a max response size.
- Only accept `image/*` content types for the cover URL.

## Acceptance criteria
- [ ] ASIN is parsed from common Amazon URL formats, with unit tests for each.
- [ ] An ISBN-10 ASIN yields a cover URL from the free source (stub the HTTP call in tests).
- [ ] When no cover is found, `cover_image_url` stays null and nothing errors.
- [ ] A non-Amazon or internal-network URL is never fetched.
- [ ] Submission latency is unaffected by cover lookup.
