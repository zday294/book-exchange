--liquibase formatted sql

--changeset bookexchange:002-create-book-submission
CREATE TABLE book_submission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange_id     BIGINT        NOT NULL REFERENCES exchange (id),
    submitter_name  VARCHAR(100)  NOT NULL,
    book_title      VARCHAR(300)  NOT NULL,
    amazon_url      VARCHAR(2048) NOT NULL,
    asin            VARCHAR(20),
    cover_image_url VARCHAR(2048),
    wrapping_paper  VARCHAR(50)   NOT NULL,
    claimed_by_name VARCHAR(100),
    claimed_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT book_submission_claim_consistent
        CHECK ((claimed_by_name IS NULL) = (claimed_at IS NULL))
);
--rollback DROP TABLE book_submission;
