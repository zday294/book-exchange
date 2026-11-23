--liquibase formatted sql

--changeset bookexchange:005-one-submission-per-person
-- A person (submitter name, case-insensitive) can submit at most one book per exchange.
-- If duplicates ever existed in a deployed database this would fail; they would have to be
-- reconciled first (e.g. delete or rename all but one submission per exchange/name).
-- The app was not deployed when this was added, so there was nothing to reconcile.
CREATE UNIQUE INDEX uq_book_submission_one_per_person
    ON book_submission (exchange_id, lower(submitter_name));
--rollback DROP INDEX uq_book_submission_one_per_person;
