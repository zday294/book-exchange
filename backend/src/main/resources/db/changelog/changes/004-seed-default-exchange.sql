--liquibase formatted sql

--changeset bookexchange:004-seed-default-exchange
INSERT INTO exchange (code, name, description)
VALUES ('XMAS2026', 'Christmas Book Exchange 2026', 'The default exchange.');
--rollback DELETE FROM exchange WHERE code = 'XMAS2026';
