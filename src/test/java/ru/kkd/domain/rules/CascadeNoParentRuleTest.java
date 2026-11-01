package ru.kkd.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

class CascadeNoParentRuleTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);
    private static final String UNIT_ID = "CIB-SOUTH";

    // В справочнике у правила major. Другая важность проверяет, что правило берёт переданную.
    private static final Severity SEVERITY = Severity.MINOR;

    private final CascadeNoParentRule rule = new CascadeNoParentRule();

    @Test
    void departmentWithoutParentDoesNotFire() {
        assertThat(check(goal("G-01", GoalLevel.DEPARTMENT, null))).isEmpty();
    }

    @Test
    void unitWithoutParentFires() {
        assertThat(check(goal("G-17", GoalLevel.UNIT, null)))
                .containsExactly(expectedIssue("G-17", "Родительская цель не указана"));
    }

    @Test
    void parentMissingFromSnapshotFires() {
        assertThat(check(goal("G-17", GoalLevel.UNIT, "G-NOPE")))
                .containsExactly(expectedIssue("G-17", "Родительская цель G-NOPE не найдена"));
    }

    @Test
    void parentOfSameLevelFires() {
        GoalRow parent = goal("G-16", GoalLevel.UNIT, "G-02");

        assertThat(check(goal("G-24", GoalLevel.UNIT, "G-16"), parent))
                .containsExactly(expectedIssue("G-24", "Родительская цель G-16 не выше по уровню"));
    }

    @Test
    void parentOfLowerLevelFires() {
        GoalRow parent = goal("G-19", GoalLevel.EMPLOYEE, "G-16");

        assertThat(check(goal("G-16", GoalLevel.UNIT, "G-19"), parent))
                .containsExactly(expectedIssue("G-16", "Родительская цель G-19 не выше по уровню"));
    }

    @Test
    void jumpOverLevelDoesNotFire() {
        GoalRow parent = goal("G-03", GoalLevel.DEPARTMENT, null);

        assertThat(check(goal("G-23", GoalLevel.EMPLOYEE, "G-03"), parent)).isEmpty();
    }

    @Test
    void closedParentDoesNotFire() {
        GoalRow closedParent = new GoalRow(
                "G-15", "Цель", UNIT_ID, GoalLevel.UNIT, "Новиков А. В.", null, "closed", null);

        assertThat(check(goal("G-21", GoalLevel.EMPLOYEE, "G-15"), closedParent)).isEmpty();
    }

    @Test
    void parentFromAnotherUnitBranchDoesNotFire() {
        GoalRow parentInNorth = new GoalRow(
                "G-05", "Цель", "CIB-NORTH", GoalLevel.UNIT, "Смирнов Д. А.", "G-01", "active", null);

        assertThat(check(goal("G-18", GoalLevel.EMPLOYEE, "G-05"), parentInNorth)).isEmpty();
    }

    @Test
    void cycleOfTwoGoalsBelowTopLevelFires() {
        GoalRow unitGoal = goal("G-A", GoalLevel.UNIT, "G-B");
        GoalRow employeeGoal = goal("G-B", GoalLevel.EMPLOYEE, "G-A");

        // У G-B родитель выше, поэтому нарушение только у G-A.
        assertThat(checkAll(unitGoal, employeeGoal))
                .containsExactly(expectedIssue("G-A", "Родительская цель G-B не выше по уровню"));
    }

    @Test
    void cycleThroughDepartmentDoesNotFire() {
        GoalRow departmentGoal = goal("G-A", GoalLevel.DEPARTMENT, "G-B");
        GoalRow unitGoal = goal("G-B", GoalLevel.UNIT, "G-A");

        assertThat(checkAll(departmentGoal, unitGoal)).isEmpty();
    }

    /** Проверяет только цель checked. Остальные цели лежат в снимке как возможные родители. */
    private List<Issue> check(GoalRow checked, GoalRow... otherGoals) {
        List<GoalRow> goals = new ArrayList<>(List.of(otherGoals));
        goals.add(checked);
        return rule.check(new Snapshot(SNAPSHOT_DATE, goals, List.of()), List.of(checked), SEVERITY);
    }

    private List<Issue> checkAll(GoalRow... goals) {
        List<GoalRow> all = List.of(goals);
        return rule.check(new Snapshot(SNAPSHOT_DATE, all, List.of()), all, SEVERITY);
    }

    private static GoalRow goal(String id, GoalLevel level, String parentGoalId) {
        return new GoalRow(id, "Цель", UNIT_ID, level, "Орлова В. П.", parentGoalId, "active", null);
    }

    private static Issue expectedIssue(String goalId, String details) {
        return new Issue(RuleCode.CASCADE_NO_PARENT, goalId, "goal", SEVERITY, goalId, UNIT_ID, details);
    }
}
