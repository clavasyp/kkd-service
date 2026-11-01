--liquibase formatted sql

--changeset kkd:probe-1
CREATE TABLE probe (
    id bigint PRIMARY KEY,
    display_name text NOT NULL
);
INSERT INTO probe (id, display_name) VALUES (1, 'Департамент корпоративного бизнеса');
--rollback DROP TABLE probe;
