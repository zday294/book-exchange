--liquibase formatted sql

--changeset bookexchange:008-party-mode
-- Party mode: a host-run queue of turns. The exchange gets a third mode, a counter for turns in a row that
-- ended without a claim (so a party nobody is playing can stop by itself), and a table holding the queue.
ALTER TABLE exchange DROP CONSTRAINT exchange_mode_check;
ALTER TABLE exchange ADD CONSTRAINT exchange_mode_check CHECK (mode IN ('SUBMIT', 'CLAIM', 'PARTY'));
ALTER TABLE exchange ADD COLUMN party_idle_turns INTEGER NOT NULL DEFAULT 0;

-- One row per person in the queue. WAITING people are in line (ordered by position; moving to the end gives
-- a new, larger position), CURRENT is the person whose turn it is, DONE had their turn and claimed a book.
CREATE TABLE party_turn (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange_id      BIGINT       NOT NULL REFERENCES exchange (id),
    participant_name VARCHAR(100) NOT NULL,
    position         BIGINT       NOT NULL,
    status           VARCHAR(10)  NOT NULL,
    turn_started_at  TIMESTAMPTZ,
    turn_ends_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT party_turn_status_check CHECK (status IN ('WAITING', 'CURRENT', 'DONE')),
    CONSTRAINT party_turn_current_has_times CHECK (
        (status = 'CURRENT' AND turn_started_at IS NOT NULL AND turn_ends_at IS NOT NULL) OR status <> 'CURRENT')
);
-- A person is in the line (waiting or current) at most once per exchange, whatever the capitalization.
CREATE UNIQUE INDEX uq_party_turn_one_active_per_person
    ON party_turn (exchange_id, lower(participant_name)) WHERE status IN ('WAITING', 'CURRENT');
-- Only one person can have the turn at a time.
CREATE UNIQUE INDEX uq_party_turn_one_current ON party_turn (exchange_id) WHERE status = 'CURRENT';
CREATE INDEX idx_party_turn_exchange_id ON party_turn (exchange_id);

--rollback DROP TABLE party_turn;
--rollback ALTER TABLE exchange DROP COLUMN party_idle_turns;
--rollback UPDATE exchange SET mode = 'CLAIM' WHERE mode = 'PARTY';
--rollback ALTER TABLE exchange DROP CONSTRAINT exchange_mode_check;
--rollback ALTER TABLE exchange ADD CONSTRAINT exchange_mode_check CHECK (mode IN ('SUBMIT', 'CLAIM'));
