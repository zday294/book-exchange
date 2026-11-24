--liquibase formatted sql

--changeset bookexchange:001-create-exchange
CREATE TABLE exchange (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(12)   NOT NULL UNIQUE,
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(1000),
    created_by_name VARCHAR(100),
    listed          BOOLEAN       NOT NULL DEFAULT TRUE,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT exchange_status_check CHECK (status IN ('OPEN', 'CLOSED'))
);
--rollback DROP TABLE exchange;
-- throwaway edit to prove the guard
