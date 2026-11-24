--liquibase formatted sql

--changeset bookexchange:009-cover-aspect-ratio
-- The cover's width divided by its height, rounded to two decimals (0.67 = a typical paperback). Filled in by the
-- cover lookup; null when no cover is known. It is the ONE fact about the cover that everyone may see before a
-- book is claimed, so the wrapped present can be drawn in the book's real proportions. Never pixel sizes.
ALTER TABLE book_submission ADD COLUMN cover_aspect_ratio NUMERIC(4, 2);
ALTER TABLE book_submission ADD CONSTRAINT book_submission_cover_aspect_ratio_check
    CHECK (cover_aspect_ratio IS NULL OR (cover_aspect_ratio >= 0.10 AND cover_aspect_ratio <= 10.00));
--rollback ALTER TABLE book_submission DROP COLUMN cover_aspect_ratio;
