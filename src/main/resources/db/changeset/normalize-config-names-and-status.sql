--liquibase formatted sql

--changeset petrm:normalize-config-names-and-status
-- config.status не обновлялся при одобрении: одобренным считался сам пользователь. Теперь решение хранится по стране: на страну одна запись config.
UPDATE config c SET status = 'a' FROM tg_user u WHERE u.id = c.tg_id AND u.wait_accept = 'a';
-- Основной клиент называется {base}_config; старый синк мог записать в БД имя второго клиента {base}_xhttp
UPDATE config SET config_name = regexp_replace(config_name, '_xhttp$', '_config') WHERE config_name ~ '_xhttp$';
--rollback SELECT 1;
