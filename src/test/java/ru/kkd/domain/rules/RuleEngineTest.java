package ru.kkd.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.kkd.domain.TestData.keyResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.kkd.domain.TestData;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

class RuleEngineTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);
    private static final LocalDate FRESH = SNAPSHOT_DATE.minusDays(1);
    private static final LocalDate STALE = SNAPSHOT_DATE.minusDays(100);

    private static final Map<RuleCode, Severity> ALL_RULES = Map.of(
            RuleCode.GOAL_NO_KR, Severity.CRITICAL,
            RuleCode.KR_WEIGHTS_SUM, Severity.MAJOR,
            RuleCode.CASCADE_NO_PARENT, Severity.MAJOR,
            RuleCode.KR_STALE_PROGRESS, Severity.MINOR);

    private final RuleEngine engine = new RuleEngine();

    @ParameterizedTest
    @ValueSource(strings = {"closed", "cancelled", "ACTIVE"})
    void inactiveGoalsWithAllDefectsAndTheirKeyResultsGiveNoIssues(String status) {
        List<GoalRow> goals = List.of(
                goal("G-1", GoalLevel.UNIT, null, status),
                goal("G-2", GoalLevel.UNIT, "G-NOPE", status));
        List<KeyResultRow> keyResults = List.of(keyResult("KR-1", "G-1", "50.00", STALE));

        List<Issue> issues = check(goals, keyResults, ALL_RULES);

        assertThat(issues).isEmpty();
    }

    @Test
    void activeGoalWithClosedParentGivesNoIssues() {
        List<GoalRow> goals = List.of(
                goal("G-15", GoalLevel.UNIT, null, "closed"),
                activeGoal("G-21", GoalLevel.EMPLOYEE, "G-15"));
        List<KeyResultRow> keyResults = List.of(keyResult("KR-21-1", "G-21", "100.00", FRESH));

        List<Issue> issues = check(goals, keyResults, ALL_RULES);

        assertThat(issues).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = RuleCode.class,
            names = {"GOAL_NO_KR", "KR_WEIGHTS_SUM", "CASCADE_NO_PARENT", "KR_STALE_PROGRESS"})
    void disabledRuleDoesNotFire(RuleCode disabledRule) {
        // G-1 нарушает три правила, G-2 только GOAL_NO_KR.
        List<GoalRow> goals = List.of(
                activeGoal("G-1", GoalLevel.UNIT, null),
                activeGoal("G-2", GoalLevel.DEPARTMENT, null));
        List<KeyResultRow> keyResults = List.of(keyResult("KR-1", "G-1", "90.00", STALE));
        Map<RuleCode, Severity> enabledRules = new HashMap<>(ALL_RULES);
        enabledRules.remove(disabledRule);

        List<Issue> issues = check(goals, keyResults, enabledRules);

        assertThat(issues)
                .extracting(Issue::ruleCode)
                .doesNotContain(disabledRule)
                .containsExactlyInAnyOrderElementsOf(enabledRules.keySet());
    }

    @Test
    void severityComesFromCatalog() {
        List<GoalRow> goals = List.of(
                activeGoal("G-1", GoalLevel.DEPARTMENT, null),
                activeGoal("G-2", GoalLevel.DEPARTMENT, null));
        List<KeyResultRow> keyResults = List.of(keyResult("KR-1", "G-2", "100.00", STALE));
        Map<RuleCode, Severity> severities = Map.of(
                RuleCode.GOAL_NO_KR, Severity.MINOR,
                RuleCode.KR_STALE_PROGRESS, Severity.CRITICAL);

        List<Issue> issues = check(goals, keyResults, severities);

        assertThat(issues)
                .extracting(Issue::severity, Issue::ruleCode, Issue::objectId)
                .containsExactly(
                        tuple(Severity.CRITICAL, RuleCode.KR_STALE_PROGRESS, "KR-1"),
                        tuple(Severity.MINOR, RuleCode.GOAL_NO_KR, "G-1"));
    }

    @Test
    void issuesAreSortedBySeverityThenRuleCodeTextThenObjectId() {
        // Строки намеренно перемешаны, чтобы порядок не достался от входных данных.
        List<GoalRow> goals = List.of(
                activeGoal("G-22", GoalLevel.EMPLOYEE, "G-TOP"),
                activeGoal("G-31", GoalLevel.UNIT, "G-TOP"),
                activeGoal("G-13", GoalLevel.UNIT, "G-TOP"),
                activeGoal("G-12", GoalLevel.UNIT, null),
                activeGoal("G-21", GoalLevel.EMPLOYEE, "G-TOP"),
                activeGoal("G-11", GoalLevel.UNIT, null),
                activeGoal("G-TOP", GoalLevel.DEPARTMENT, null));
        List<KeyResultRow> keyResults = List.of(
                keyResult("KR-02", "G-31", "50.00", STALE),
                keyResult("KR-01", "G-31", "50.00", STALE),
                keyResult("KR-13", "G-13", "90.00", FRESH),
                keyResult("KR-12", "G-12", "100.00", FRESH),
                keyResult("KR-11", "G-11", "90.00", FRESH),
                keyResult("KR-TOP", "G-TOP", "100.00", FRESH));

        List<Issue> issues = check(goals, keyResults, ALL_RULES);

        // CASCADE_NO_PARENT идёт раньше KR_WEIGHTS_SUM по тексту кода, хотя в RuleCode стоит позже.
        assertThat(issues)
                .extracting(Issue::severity, Issue::ruleCode, Issue::objectId)
                .containsExactly(
                        tuple(Severity.CRITICAL, RuleCode.GOAL_NO_KR, "G-21"),
                        tuple(Severity.CRITICAL, RuleCode.GOAL_NO_KR, "G-22"),
                        tuple(Severity.MAJOR, RuleCode.CASCADE_NO_PARENT, "G-11"),
                        tuple(Severity.MAJOR, RuleCode.CASCADE_NO_PARENT, "G-12"),
                        tuple(Severity.MAJOR, RuleCode.KR_WEIGHTS_SUM, "G-11"),
                        tuple(Severity.MAJOR, RuleCode.KR_WEIGHTS_SUM, "G-13"),
                        tuple(Severity.MINOR, RuleCode.KR_STALE_PROGRESS, "KR-01"),
                        tuple(Severity.MINOR, RuleCode.KR_STALE_PROGRESS, "KR-02"));
    }

    @Test
    void laterChangesOfSourceCollectionsDoNotAffectSnapshotAndCatalog() {
        List<GoalRow> goals = new ArrayList<>(List.of(
                activeGoal("G-1", GoalLevel.DEPARTMENT, null),
                activeGoal("G-2", GoalLevel.DEPARTMENT, null)));
        List<KeyResultRow> keyResults = new ArrayList<>(List.of(keyResult("KR-2", "G-2", "100.00", FRESH)));
        Map<RuleCode, Severity> severities = new HashMap<>(ALL_RULES);
        Snapshot snapshot = new Snapshot(SNAPSHOT_DATE, goals, keyResults);
        RuleCatalog catalog = new RuleCatalog(severities);

        goals.clear();
        keyResults.clear();
        severities.clear();

        // Без копии целей или справочника список будет пуст, без копии КР сработает ещё и G-2.
        assertThat(engine.check(snapshot, catalog))
                .extracting(Issue::ruleCode, Issue::objectId)
                .containsExactly(tuple(RuleCode.GOAL_NO_KR, "G-1"));
    }

    @Test
    void snapshotAndCatalogCollectionsAreReadOnly() {
        Snapshot snapshot = new Snapshot(SNAPSHOT_DATE, new ArrayList<>(), new ArrayList<>());
        RuleCatalog catalog = new RuleCatalog(new HashMap<>());

        assertThatThrownBy(() -> snapshot.goals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.keyResults().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.severities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private List<Issue> check(
            List<GoalRow> goals, List<KeyResultRow> keyResults, Map<RuleCode, Severity> severities) {
        return engine.check(new Snapshot(SNAPSHOT_DATE, goals, keyResults), new RuleCatalog(severities));
    }

    private static GoalRow activeGoal(String id, GoalLevel level, String parentGoalId) {
        return goal(id, level, parentGoalId, "active");
    }

    private static GoalRow goal(String id, GoalLevel level, String parentGoalId, String status) {
        return TestData.goal(id, "CIB-NORTH", level, parentGoalId, status);
    }
}
