# 10 – Wrapping paper resources (spike + implementation)

## Story
As a participant, I want a selection of wrapping papers to choose from so that my book looks like a real, individual present.

## Decision to make: drawn graphic vs. static PNG
**Recommendation: SVG, rendered as a reusable Vue component.**

| | Static PNG | SVG patterns |
|---|---|---|
| Crispness at any size | Needs multiple resolutions | Always sharp |
| Adding a new paper | Need an artist/export | Add a small definition |
| Recoloring (e.g. ribbon color, dark mode) | Re-export | Change a variable |
| Present shape/ribbon/tag on top | Must be baked in or layered | Composes naturally |
| Effort to start | Low if you have art | Low–medium |

### Suggested approach
- Define each paper as data: `{ key, label, baseColor, patternType, accentColor, ribbonColor }`.
- A `<WrappedPresent :paper="..." :tag="..." />` component draws a box + ribbon + bow in SVG, filling the box with an SVG `<pattern>` chosen by `patternType` (stripes, dots, snowflakes, plaid, stars, holly).
- Start with ~6 papers. Real PNG/artwork can replace a pattern later by allowing a paper to optionally specify an `imageUrl` that the component uses instead.

## Details
- Front end owns the visual definitions; the backend only stores and validates the paper **key** (story 03).
- Keep the key list in one place on each side (shared constant in the frontend, an enum/config list in the backend) and note in the README that they must match. A `GET /api/wrapping-papers` endpoint could remove the duplication — optional.
- Components: `WrappingPaperPicker` (used in story 07) and `WrappedPresent` (used in story 09).

## Acceptance criteria
- [ ] At least 6 distinct papers are defined with keys, labels and colors.
- [ ] `WrappedPresent` renders any paper key at arbitrary size and shows a name tag.
- [ ] `WrappingPaperPicker` lists all papers and emits the selected key.
- [ ] Unknown keys fall back to a default paper instead of breaking the page.
- [ ] Backend rejects keys not in its list.
