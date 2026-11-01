package ru.kkd.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.TestData;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

class GoalNoKrRuleTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);

    // В справочнике у правила critical. Другая важность проверяет, что правило берёт переданную.
    private static final Severity SEVERITY = Severity.MINOR;

    private static final String DETAILS = "У активной цели нет ни одного ключевого результата";

    private final GoalNoKrRule rule = new GoalNoKrRule();

    @Test
    void goalWithoutKeyResultsFires() {
        GoalRow goal = goal("G-09");

        assertThat(check(List.of(goal), List.of())).containsExactly(expectedIssue("G-09"));
    }

    @Test
    void goalWithOneKeyResultDoesNotFire() {
        GoalRow goal = goal("G-09");
        List<KeyResultRow> keyResults = List.of(keyResult("KR-09-1", "G-09"));

        assertThat(check(List.of(goal), keyResults)).isEmpty();
    }

    @Test
    void keyResultOfAnotherGoalDoesNotCount() {
        List<GoalRow> goals = List.of(goal("G-09"), goal("G-05"));
        List<KeyResultRow> keyResults = List.of(keyResult("KR-05-1", "G-05"));

        assertThat(check(goals, keyResults)).containsExactly(expectedIssue("G-09"));
    }

    private List<Issue> check(List<GoalRow> goals, List<KeyResultRow> keyResults) {
        return rule.check(new Snapshot(SNAPSHOT_DATE, goals, keyResults), goals, SEVERITY);
    }

    private static GoalRow goal(String id) {
        return TestData.activeGoal(id, "CIB-NORTH", GoalLevel.EMPLOYEE, "G-01");
    }

    private static KeyResultRow keyResult(String id, String goalId) {
        return TestData.keyResult(id, goalId, "100.00", SNAPSHOT_DATE);
    }

    private static Issue expectedIssue(String goalId) {
        return TestData.goalIssue(RuleCode.GOAL_NO_KR, goalId, "CIB-NORTH", SEVERITY, DETAILS);
    }
}
