package ru.kkd.domain.model;

/** Все 6 кодов из задания. GOAL_NO_OWNER и KR_NO_TARGET есть в справочнике, но правил для них нет. */
public enum RuleCode {
    GOAL_NO_OWNER,
    GOAL_NO_KR,
    KR_WEIGHTS_SUM,
    CASCADE_NO_PARENT,
    KR_NO_TARGET,
    KR_STALE_PROGRESS
}
