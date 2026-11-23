-- Christmas Book Exchange: initial schema.
-- Safe to run more than once (uses IF NOT EXISTS / ON CONFLICT DO NOTHING).
-- Migration tooling (Flyway/Liquibase) can replace this later.

CREATE TABLE IF NOT EXISTS exchange (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(12)   NOT NULL UNIQUE,   -- short shareable code used in URLs and API paths
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(1000),
    created_by_name VARCHAR(100),                    -- organizer's name as typed
    listed          BOOLEAN       NOT NULL DEFAULT TRUE,  -- FALSE = join by code/link only
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT exchange_status_check CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE TABLE IF NOT EXISTS book_submission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange_id     BIGINT        NOT NULL REFERENCES exchange (id),
    submitter_name  VARCHAR(100)  NOT NULL,
    book_title      VARCHAR(300)  NOT NULL,
    amazon_url      VARCHAR(2048) NOT NULL,
    asin            VARCHAR(20),                     -- Amazon product id parsed from the link
    cover_image_url VARCHAR(2048),                   -- NULL = no cover found
    wrapping_paper  VARCHAR(50)   NOT NULL,          -- key of the paper design
    claimed_by_name VARCHAR(100),                    -- NULL = unclaimed
    claimed_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT book_submission_claim_consistent
        CHECK ((claimed_by_name IS NULL) = (claimed_at IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_book_submission_exchange_id
    ON book_submission (exchange_id);

-- A person can claim at most one book per exchange (case-insensitive).
CREATE UNIQUE INDEX IF NOT EXISTS uq_book_submission_one_claim_per_person
    ON book_submission (exchange_id, lower(claimed_by_name))
    WHERE claimed_by_name IS NOT NULL;

-- Seed one open exchange so the app is usable immediately.
INSERT INTO exchange (code, name, description, created_by_name)
VALUES ('XMAS2026', 'Christmas Book Exchange 2026', 'The default exchange.', NULL)
ON CONFLICT (code) DO NOTHING;
