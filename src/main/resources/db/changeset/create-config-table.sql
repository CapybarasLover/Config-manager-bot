--liquibase formatted sql

--changeset petrm:create-config-table

CREATE TABLE config(
    tg_id           BIGINT      UNIQUE      NOT NULL    PRIMARY KEY,
    config_name     TEXT        NOT NULL,
    vless_link      TEXT        NOT NULL,
    sub_link        TEXT        NOT NULL,

    CONSTRAINT fk_tg_id
        FOREIGN KEY (tg_id) REFERENCES tg_user(id)
);

--rollback DROP TABLE config;

