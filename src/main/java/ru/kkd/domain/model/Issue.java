package ru.kkd.domain.model;

/** Найденная проблема. У проблемы цели objectId и goalId совпадают. */
public record Issue(
        RuleCode ruleCode,
        String objectId,
        String objectType,
        Severity severity,
        String goalId,
        String unitId,
        String details) {

    public static final String OBJECT_TYPE_GOAL = "goal";
    public static final String OBJECT_TYPE_KEY_RESULT = "key_result";
}
