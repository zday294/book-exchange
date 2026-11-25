--liquibase formatted sql

--changeset bookexchange:010-rename-amazon-url-to-book-url
-- Books can now be linked from Amazon or Barnes & Noble, so "amazon_url" becomes the store-neutral "book_url".
-- A pure rename: existing links keep their values.
ALTER TABLE book_submission RENAME COLUMN amazon_url TO book_url;
--rollback ALTER TABLE book_submission RENAME COLUMN book_url TO amazon_url;
