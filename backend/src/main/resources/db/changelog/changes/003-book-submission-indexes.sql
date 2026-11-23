--liquibase formatted sql

--changeset bookexchange:003-book-submission-exchange-index
CREATE INDEX idx_book_submission_exchange_id ON book_submission (exchange_id);
--rollback DROP INDEX idx_book_submission_exchange_id;

--changeset bookexchange:003-book-submission-one-claim-per-person
-- A person can claim at most one book per exchange (case-insensitive).
CREATE UNIQUE INDEX uq_book_submission_one_claim_per_person
    ON book_submission (exchange_id, lower(claimed_by_name))
    WHERE claimed_by_name IS NOT NULL;
--rollback DROP INDEX uq_book_submission_one_claim_per_person;
