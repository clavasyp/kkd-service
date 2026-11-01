--liquibase formatted sql

--changeset kkd:008-goal-snapshot
CREATE TABLE goal_snapshot (
    run_id         TEXT REFERENCES kkd_run (id),
    goal_id        TEXT,
    title          TEXT NOT NULL,
    unit_id        TEXT NOT NULL,
    level          TEXT NOT NULL,
    owner_name     TEXT,
    parent_goal_id TEXT,
    status         TEXT NOT NULL,
    due_date       DATE,
    PRIMARY KEY (run_id, goal_id)
);
--rollback DROP TABLE goal_snapshot;

--changeset kkd:009-kr-snapshot
CREATE TABLE key_result_snapshot (
    run_id          TEXT,
    kr_id           TEXT,
    goal_id         TEXT NOT NULL,
    title           TEXT NOT NULL,
    measure_unit    TEXT NOT NULL,
    target_value    NUMERIC(14,2),
    fact_value      NUMERIC(14,2),
    weight          NUMERIC(6,2),
    fact_updated_on DATE,
    PRIMARY KEY (run_id, kr_id),
    FOREIGN KEY (run_id, goal_id) REFERENCES goal_snapshot (run_id, goal_id)
);
--rollback DROP TABLE key_result_snapshot;
