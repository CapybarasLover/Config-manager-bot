--liquibase formatted sql

--changeset petrm:create-user-table

CREATE TABLE tg_user (
    id       BIGINT      UNIQUE      PRIMARY KEY,
    tg_name     TEXT,
    has_config  BOOLEAN     NOT NULL,
    wait_accept CHAR(1)     CHECK(wait_accept in ('w', 'd', 'a'))   DEFAULT 'w'
);

--rollback DROP TABLE tg_user;
