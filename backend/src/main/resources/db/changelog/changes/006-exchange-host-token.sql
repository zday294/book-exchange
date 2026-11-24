--liquibase formatted sql

--changeset bookexchange:006-exchange-host-token
-- Only a hash of the host token is stored (see HostTokens): the token itself is shown once, when the
-- exchange is created. Exchanges that already exist have no token, and so no host.
ALTER TABLE exchange ADD COLUMN host_token_hash VARCHAR(64);
--rollback ALTER TABLE exchange DROP COLUMN host_token_hash;
