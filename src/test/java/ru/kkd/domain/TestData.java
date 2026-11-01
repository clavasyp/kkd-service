package ru.kkd.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

/** Построители данных для тестов домена. Поля, которые в проверках не участвуют, заполнены постоянными значениями. */
public final class TestData {

    private TestData() {
    }

    public static GoalRow goal(String id, String unitId, GoalLevel level, String parentGoalId, String status) {
        return new GoalRow(id, "Цель", unitId, level, "Иванов И. И.", parentGoalId, status, null);
    }

    public static GoalRow activeGoal(String id, String unitId, GoalLevel level, String parentGoalId) {
        return goal(id, unitId, level, parentGoalId, "active");
    }

    /** Вес может быть null: пустой вес это отдельный случай правила. */
    public static KeyResultRow keyResult(String id, String goalId, String weight, LocalDate factUpdatedOn) {
        return new KeyResultRow(
                id, goalId, "КР", "шт.", BigDecimal.TEN, BigDecimal.ONE,
                weight == null ? null : new BigDecimal(weight), factUpdatedOn);
    }

    // Тип объекта задан текстом, а не константой Issue: тест сверяет значение, которое уходит в базу и API.
    public static Issue goalIssue(RuleCode ruleCode, String goalId, String unitId, Severity severity, String details) {
        return new Issue(ruleCode, goalId, "goal", severity, goalId, unitId, details);
    }

    public static Issue keyResultIssue(
            RuleCode ruleCode, String keyResultId, String goalId, String unitId, Severity severity, String details) {
        return new Issue(ruleCode, keyResultId, "key_result", severity, goalId, unitId, details);
    }

    // Проблемы с важностью из справочника, раздел 3.1 design.md. В расчётах текст details не участвует.

    public static Issue noKrIssue(String goalId, String unitId) {
        return goalIssue(RuleCode.GOAL_NO_KR, goalId, unitId, Severity.CRITICAL, "Текст");
    }

    public static Issue weightsSumIssue(String goalId, String unitId) {
        return goalIssue(RuleCode.KR_WEIGHTS_SUM, goalId, unitId, Severity.MAJOR, "Текст");
    }

    public static Issue noParentIssue(String goalId, String unitId) {
        return goalIssue(RuleCode.CASCADE_NO_PARENT, goalId, unitId, Severity.MAJOR, "Текст");
    }

    public static Issue staleIssue(String keyResultId, String goalId, String unitId) {
        return keyResultIssue(RuleCode.KR_STALE_PROGRESS, keyResultId, goalId, unitId, Severity.MINOR, "Текст");
    }
}
