--liquibase formatted sql

--changeset bookexchange:011-rename-asin-to-book-identifier
-- The cover lookup now also reads Barnes & Noble links (an ISBN-13 in the "ean" parameter), so the
-- Amazon-only "asin" column becomes the store-neutral "book_identifier": the ASIN / ISBN-10 for Amazon,
-- the ISBN-13 for Barnes & Noble. A pure rename: existing values are kept.
ALTER TABLE book_submission RENAME COLUMN asin TO book_identifier;
--rollback ALTER TABLE book_submission RENAME COLUMN book_identifier TO asin;
