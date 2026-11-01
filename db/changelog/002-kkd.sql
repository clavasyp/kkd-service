--liquibase formatted sql

--changeset kkd:004-kkd-rule
CREATE TABLE kkd_rule (
    code        TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    severity    TEXT NOT NULL CHECK (severity IN ('critical', 'major', 'minor')),
    object_type TEXT NOT NULL CHECK (object_type IN ('goal', 'key_result')),
    enabled     BOOLEAN NOT NULL
);
--rollback DROP TABLE kkd_rule;

--changeset kkd:005-kkd-rule-rows
INSERT INTO kkd_rule (code, title, severity, object_type, enabled) VALUES
    ('GOAL_NO_OWNER', 'Цель без ответственного', 'critical', 'goal', false),
    ('GOAL_NO_KR', 'У активной цели нет ни одного ключевого результата', 'critical', 'goal', true),
    ('KR_WEIGHTS_SUM', 'Сумма весов КР цели не равна 100%', 'major', 'goal', true),
    ('CASCADE_NO_PARENT', 'Цель не связана с целью вышестоящего уровня', 'major', 'goal', true),
    ('KR_NO_TARGET', 'У КР не заполнено целевое значение', 'major', 'key_result', false),
    ('KR_STALE_PROGRESS', 'Факт КР не обновлялся более 45 дней', 'minor', 'key_result', true);
--rollback DELETE FROM kkd_rule;

--changeset kkd:006-kkd-run
CREATE TABLE kkd_run (
    id                 TEXT COLLATE "C" PRIMARY KEY,
    snapshot_date      DATE NOT NULL,
    status             TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
    compared_to_run_id TEXT REFERENCES kkd_run (id),
    error              TEXT
);
--rollback DROP TABLE kkd_run;

--changeset kkd:007-kkd-issue
CREATE TABLE kkd_issue (
    run_id      TEXT REFERENCES kkd_run (id),
    rule_code   TEXT COLLATE "C" REFERENCES kkd_rule (code),
    object_id   TEXT COLLATE "C",
    object_type TEXT NOT NULL CHECK (object_type IN ('goal', 'key_result')),
    severity    TEXT NOT NULL,
    goal_id     TEXT NOT NULL,
    unit_id     TEXT NOT NULL,
    details     TEXT NOT NULL,
    PRIMARY KEY (run_id, rule_code, object_id)
);
--rollback LOCK TABLE kkd_run IN ACCESS EXCLUSIVE MODE;
--rollback DROP TABLE kkd_issue;
