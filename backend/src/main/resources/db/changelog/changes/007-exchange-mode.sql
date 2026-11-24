--liquibase formatted sql

--changeset bookexchange:007-exchange-mode
-- An exchange is in exactly one mode: people submit books (SUBMIT) or people claim them (CLAIM).
-- Existing exchanges start in SUBMIT. The status column (OPEN/CLOSED) is separate: it means "finished".
-- Later modes (for example a party with turns) widen the allowed values in a new changeset.
ALTER TABLE exchange ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'SUBMIT';
ALTER TABLE exchange ADD CONSTRAINT exchange_mode_check CHECK (mode IN ('SUBMIT', 'CLAIM'));
--rollback ALTER TABLE exchange DROP CONSTRAINT exchange_mode_check;
--rollback ALTER TABLE exchange DROP COLUMN mode;
