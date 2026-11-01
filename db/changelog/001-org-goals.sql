--liquibase formatted sql

--changeset kkd:001-unit
CREATE TABLE unit (
    id        TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    parent_id TEXT REFERENCES unit (id)
);
--rollback DROP TABLE unit;

--changeset kkd:002-goal
CREATE TABLE goal (
    id             TEXT PRIMARY KEY,
    title          TEXT NOT NULL,
    unit_id        TEXT NOT NULL REFERENCES unit (id),
    level          TEXT NOT NULL CHECK (level IN ('department', 'unit', 'employee')),
    owner_name     TEXT,
    parent_goal_id TEXT REFERENCES goal (id),
    status         TEXT NOT NULL CHECK (status IN ('active', 'closed', 'cancelled')),
    due_date       DATE
);
--rollback DROP TABLE goal;

--changeset kkd:003-key-result
CREATE TABLE key_result (
    id              TEXT PRIMARY KEY,
    goal_id         TEXT NOT NULL REFERENCES goal (id),
    title           TEXT NOT NULL,
    measure_unit    TEXT NOT NULL,
    target_value    NUMERIC(14,2),
    fact_value      NUMERIC(14,2),
    weight          NUMERIC(6,2),
    fact_updated_on DATE
);
--rollback DROP TABLE key_result;
