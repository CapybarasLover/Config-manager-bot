--liquibase formatted sql

--changeset petrm:add-reality-link-column
ALTER TABLE config ADD COLUMN IF NOT EXISTS reality_link TEXT;
--rollback ALTER TABLE config DROP COLUMN IF EXISTS reality_link;

--changeset petrm:clear-latv-xhttp-link
-- XHTTP на Риге убран — старые xHTTP-ссылки латвийских конфигов больше не работают
UPDATE config SET xhttp_link = NULL WHERE country = 'latv';
--rollback SELECT 1;
