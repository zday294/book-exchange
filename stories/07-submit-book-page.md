# 07 – Submit book page

## Story
As a participant, I want a form to submit a book and choose its wrapping paper so that my gift appears in the pile.

## Depends on
03 (endpoint), 06 (scaffold), 10 (wrapping paper resources). Name field behavior builds on 08.

## Details
- Route: `/exchange/:exchangeCode/submit`.
- Fields:
  - **Your name** – prefilled from the saved name (story 08) if present, editable.
  - **Book name**
  - **Amazon link**
  - **Wrapping paper** – a visual picker showing each available paper as a swatch/thumbnail; one must be selected. A live preview of a wrapped present with the selected paper is a nice-to-have.
- The book cover is fetched by the backend after submission (story 14); the form needs no image field.
- Client-side validation mirroring the backend (required fields, URL looks like an Amazon link).
- Server validation errors (400) are shown next to the relevant fields.
- On success: show a confirmation ("Your book is under the tree!") and link to the exchange page; clear the book fields but keep the name.

## Acceptance criteria
- [ ] Form cannot submit with missing/invalid fields.
- [ ] Selecting a paper highlights it; submission sends its key as `wrappingPaper`.
- [ ] Successful submission creates the book (visible in the pile on story 09's page).
- [ ] Backend errors are displayed without losing the user's input.
- [ ] Usable on a phone-sized screen.
