package ru.kkd.domain.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.kkd.domain.TestData.activeGoal;
import static ru.kkd.domain.TestData.goal;
import static ru.kkd.domain.TestData.keyResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.delta.BaseRunSelector;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.metrics.MetricComparison;
import ru.kkd.domain.metrics.MetricKind;
import ru.kkd.domain.metrics.Trend;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.org.UnitTree;
import ru.kkd.domain.rules.RuleCatalog;
import ru.kkd.domain.rules.RuleEngine;

/**
 * Проблемы здесь находит настоящий компонент правил: примеры описаны данными (нет КР, веса 90, факту 46 дней),
 * и тест проверяет всю цепочку от снимка до сводки. Числа в ожиданиях сравниваются вместе с масштабом.
 */
class SummaryAssemblerTest {

    private static final RuleEngine RULE_ENGINE = new RuleEngine();
    private static final RuleCatalog CATALOG = new RuleCatalog(Map.of(
            RuleCode.GOAL_NO_KR, Severity.CRITICAL,
            RuleCode.KR_WEIGHTS_SUM, Severity.MAJOR,
            RuleCode.CASCADE_NO_PARENT, Severity.MAJOR,
            RuleCode.KR_STALE_PROGRESS, Severity.MINOR));

    private static final LocalDate DATE_1 = LocalDate.of(2026, 10, 19);
    private static final LocalDate DATE_2 = LocalDate.of(2026, 10, 26);
    private static final LocalDate FRESH_FACT = LocalDate.of(2026, 10, 12);
    // На 19.10 факту 39 дней, на 26.10 уже 46: нарушение появляется без единой правки.
    private static final LocalDate AGING_FACT = LocalDate.of(2026, 9, 10);

    private static final Unit DEP = new Unit("DEP", "Департамент", null);
    private static final Unit NORTH = new Unit("N", "Управление Север", "DEP");
    private static final Unit SOUTH = new Unit("S", "Управление Юг", "DEP");
    private static final Unit EMPTY = new Unit("EMPTY", "Подразделение без целей", "DEP");
    private static final UnitTree TREE = new UnitTree(List.of(DEP, NORTH, SOUTH, EMPTY));

    // Родитель целей A–D закрыт: это не нарушение каскада, и ни в один знаменатель он не входит.
    private static final GoalRow CLOSED_PARENT = goal("TOP", "DEP", GoalLevel.DEPARTMENT, null, "closed");
    private static final GoalRow A = unitGoal("A", "N");
    private static final GoalRow B = unitGoal("B", "N");
    private static final GoalRow C = unitGoal("C", "N");
    private static final GoalRow D = unitGoal("D", "S");
    private static final GoalRow E = activeGoal("E", "S", GoalLevel.UNIT, null);

    // У A нет КР, у B веса 90, C и D чистые.
    private static final RunData RUN_1 = completedRun(
            "RUN-1", DATE_1, null,
            List.of(CLOSED_PARENT, A, B, C, D),
            List.of(
                    keyResult("KR-B", "B", "90.00", FRESH_FACT),
                    keyResult("KR-C", "C", "100.00", AGING_FACT),
                    keyResult("KR-D", "D", "100.00", FRESH_FACT)));

    // A та же, B исправлена, факт C постарел, D чистая, появилась E без родителя.
    private static final RunData RUN_2 = completedRun(
            "RUN-2", DATE_2, "RUN-1",
            List.of(CLOSED_PARENT, A, B, C, D, E),
            List.of(
                    keyResult("KR-B", "B", "100.00", FRESH_FACT),
                    keyResult("KR-C", "C", "100.00", AGING_FACT),
                    keyResult("KR-D", "D", "100.00", FRESH_FACT),
                    keyResult("KR-E", "E", "100.00", FRESH_FACT)));

    private final SummaryAssembler assembler = new SummaryAssembler();

    @Test
    void exampleDataGiveExpectedIssues() {
        assertThat(RUN_1.issues())
                .extracting(Issue::ruleCode, Issue::objectId, Issue::unitId)
                .containsExactlyInAnyOrder(
                        tuple(RuleCode.GOAL_NO_KR, "A", "N"),
                        tuple(RuleCode.KR_WEIGHTS_SUM, "B", "N"));
        assertThat(RUN_2.issues())
                .extracting(Issue::ruleCode, Issue::objectId, Issue::unitId)
                .containsExactlyInAnyOrder(
                        tuple(RuleCode.GOAL_NO_KR, "A", "N"),
                        tuple(RuleCode.KR_STALE_PROGRESS, "KR-C", "N"),
                        tuple(RuleCode.CASCADE_NO_PARENT, "E", "S"));
    }

    @Test
    void departmentIsCountedWithChildren() {
        Summary summary = assembler.assemble(RUN_2, RUN_1, DEP, TREE);

        assertThat(summary.run()).isEqualTo(RUN_2.run());
        assertThat(summary.comparedTo()).isEqualTo(RUN_1.run());
        assertThat(summary.unit()).isEqualTo(DEP);
        assertThat(summary.metrics()).containsExactly(
                share("40.0", "50.0", "-10.0", Trend.WORSE),
                total("3", "2", "1", Trend.WORSE),
                coverage("80.0", "100.0", "-20.0", Trend.WORSE));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, 1, 0),
                new SeverityCount(Severity.MAJOR, 1, 1, 0),
                new SeverityCount(Severity.MINOR, 1, 0, 1));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(2, 1, 1));
    }

    @Test
    void northIsCountedByItsOwnBranch() {
        Summary summary = assembler.assemble(RUN_2, RUN_1, NORTH, TREE);

        assertThat(summary.comparedTo()).isEqualTo(RUN_1.run());
        assertThat(summary.unit()).isEqualTo(NORTH);
        assertThat(summary.metrics()).containsExactly(
                share("33.3", "33.3", "0.0", Trend.SAME),
                total("2", "2", "0", Trend.SAME),
                coverage("100.0", "100.0", "0.0", Trend.SAME));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, 1, 0),
                new SeverityCount(Severity.MAJOR, 0, 1, -1),
                new SeverityCount(Severity.MINOR, 1, 0, 1));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(1, 1, 1));
    }

    @Test
    void southIsCountedByItsOwnBranch() {
        Summary summary = assembler.assemble(RUN_2, RUN_1, SOUTH, TREE);

        assertThat(summary.comparedTo()).isEqualTo(RUN_1.run());
        assertThat(summary.unit()).isEqualTo(SOUTH);
        assertThat(summary.metrics()).containsExactly(
                share("50.0", "100.0", "-50.0", Trend.WORSE),
                total("1", "0", "1", Trend.WORSE),
                coverage("50.0", "100.0", "-50.0", Trend.WORSE));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, 0, 0),
                new SeverityCount(Severity.MAJOR, 1, 0, 1),
                new SeverityCount(Severity.MINOR, 0, 0, 0));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(1, 0, 0));
    }

    @Test
    void deltaOfShareIsCountedFromRoundedValues() {
        // В N чиста 1 цель из 3, затем 2 из 3: 66.7 - 33.3 = 33.4. Из неокруглённых значений вышло бы 33.3.
        RunData current = completedRun(
                "RUN-2", DATE_2, "RUN-1",
                List.of(CLOSED_PARENT, A, B, C),
                List.of(keyResult("KR-B", "B", "100.00", FRESH_FACT), keyResult("KR-C", "C", "100.00", FRESH_FACT)));

        Summary summary = assembler.assemble(current, RUN_1, NORTH, TREE);

        assertThat(summary.metrics().get(0)).isEqualTo(share("66.7", "33.3", "33.4", Trend.BETTER));
    }

    @Test
    void firstRunOfDepartmentHasNoComparison() {
        Summary summary = assembler.assemble(RUN_1, null, DEP, TREE);

        assertThat(summary.run()).isEqualTo(RUN_1.run());
        assertThat(summary.comparedTo()).isNull();
        assertThat(summary.unit()).isEqualTo(DEP);
        assertThat(summary.metrics()).containsExactly(
                share("50.0", null, null, null),
                total("2", null, null, null),
                coverage("100.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, null, null),
                new SeverityCount(Severity.MAJOR, 1, null, null),
                new SeverityCount(Severity.MINOR, 0, null, null));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void firstRunOfNorthHasNoComparison() {
        Summary summary = assembler.assemble(RUN_1, null, NORTH, TREE);

        assertThat(summary.comparedTo()).isNull();
        assertThat(summary.metrics()).containsExactly(
                share("33.3", null, null, null),
                total("2", null, null, null),
                coverage("100.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, null, null),
                new SeverityCount(Severity.MAJOR, 1, null, null),
                new SeverityCount(Severity.MINOR, 0, null, null));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void firstRunOfSouthHasNoComparisonAndNullsInsteadOfZeros() {
        Summary summary = assembler.assemble(RUN_1, null, SOUTH, TREE);

        assertThat(summary.comparedTo()).isNull();
        assertThat(summary.metrics()).containsExactly(
                share("100.0", null, null, null),
                total("0", null, null, null),
                coverage("100.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, null, null),
                new SeverityCount(Severity.MAJOR, 0, null, null),
                new SeverityCount(Severity.MINOR, 0, null, null));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void allPreviousRunsFailedLooksLikeFirstRun() {
        Run failed = new Run("RUN-0", DATE_1, RunStatus.FAILED, null, "Прогон упал");
        Run currentRun = new Run("RUN-2", DATE_2, RunStatus.COMPLETED, null, null);
        RunData current = new RunData(currentRun, RUN_2.goals(), RUN_2.issues());

        assertThat(new BaseRunSelector().select(currentRun, List.of(failed, currentRun))).isEmpty();
        Summary summary = assembler.assemble(current, null, DEP, TREE);

        assertThat(summary.run()).isEqualTo(currentRun);
        assertThat(summary.comparedTo()).isNull();
        assertThat(summary.metrics()).containsExactly(
                share("40.0", null, null, null),
                total("3", null, null, null),
                coverage("80.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, null, null),
                new SeverityCount(Severity.MAJOR, 1, null, null),
                new SeverityCount(Severity.MINOR, 1, null, null));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void noActiveGoalsInBranchBeforeGivesNullPreviousSharesAndZeroPreviousCounts() {
        // В прошлом прогоне в S была только закрытая цель, она активной не считается.
        RunData base = completedRun(
                "RUN-1", DATE_1, null,
                List.of(CLOSED_PARENT, goal("OLD", "S", GoalLevel.UNIT, "TOP", "closed")),
                List.of());
        RunData current = completedRun(
                "RUN-2", DATE_2, "RUN-1",
                List.of(CLOSED_PARENT, D, E),
                List.of(keyResult("KR-D", "D", "100.00", FRESH_FACT), keyResult("KR-E", "E", "100.00", FRESH_FACT)));

        Summary summary = assembler.assemble(current, base, SOUTH, TREE);

        assertThat(summary.comparedTo()).isEqualTo(base.run());
        assertThat(summary.metrics()).containsExactly(
                share("50.0", null, null, null),
                total("1", "0", "1", Trend.WORSE),
                coverage("50.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, 0, 0),
                new SeverityCount(Severity.MAJOR, 1, 0, 1),
                new SeverityCount(Severity.MINOR, 0, 0, 0));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(1, 0, 0));
    }

    @Test
    void noActiveGoalsInBranchNowGivesNullSharesAndZeroCounts() {
        // Единственную цель S с дефектом закрыли: проблема ушла как resolved, считать доли не из чего.
        List<KeyResultRow> keyResults = List.of(keyResult("KR-D", "D", "90.00", FRESH_FACT));
        RunData base = completedRun("RUN-1", DATE_1, null, List.of(CLOSED_PARENT, D), keyResults);
        RunData current = completedRun(
                "RUN-2", DATE_2, "RUN-1",
                List.of(CLOSED_PARENT, goal("D", "S", GoalLevel.UNIT, "TOP", "closed")),
                keyResults);

        Summary summary = assembler.assemble(current, base, SOUTH, TREE);

        assertThat(summary.comparedTo()).isEqualTo(base.run());
        assertThat(summary.metrics()).containsExactly(
                share(null, "0.0", null, null),
                total("0", "1", "-1", Trend.BETTER),
                coverage(null, "100.0", null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, 0, 0),
                new SeverityCount(Severity.MAJOR, 0, 1, -1),
                new SeverityCount(Severity.MINOR, 0, 0, 0));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(0, 0, 1));
    }

    @Test
    void unitWithoutGoalsGivesNullSharesAndZeroCounts() {
        Summary summary = assembler.assemble(RUN_2, RUN_1, EMPTY, TREE);

        assertThat(summary.comparedTo()).isEqualTo(RUN_1.run());
        assertThat(summary.unit()).isEqualTo(EMPTY);
        assertThat(summary.metrics()).containsExactly(
                share(null, null, null, null),
                total("0", "0", "0", Trend.SAME),
                coverage(null, null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, 0, 0),
                new SeverityCount(Severity.MAJOR, 0, 0, 0),
                new SeverityCount(Severity.MINOR, 0, 0, 0));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(0, 0, 0));
    }

    @Test
    void unitWithoutGoalsOnFirstRunGivesNullsAndZeroValue() {
        Summary summary = assembler.assemble(RUN_1, null, EMPTY, TREE);

        assertThat(summary.comparedTo()).isNull();
        assertThat(summary.metrics()).containsExactly(
                share(null, null, null, null),
                total("0", null, null, null),
                coverage(null, null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 0, null, null),
                new SeverityCount(Severity.MAJOR, 0, null, null),
                new SeverityCount(Severity.MINOR, 0, null, null));
        assertThat(summary.delta()).isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void goalMovedBetweenUnitsCountsInSnapshotOfItsRunAndPersistsWhereItIsNow() {
        // X с весами 90 перешла из N в S, а Y всё время чистая в S.
        GoalRow stayingInSouth = unitGoal("Y", "S");
        List<KeyResultRow> keyResults = List.of(
                keyResult("KR-X", "X", "90.00", FRESH_FACT),
                keyResult("KR-Y", "Y", "100.00", FRESH_FACT));
        RunData base = completedRun(
                "RUN-1", DATE_1, null, List.of(CLOSED_PARENT, unitGoal("X", "N"), stayingInSouth), keyResults);
        RunData current = completedRun(
                "RUN-2", DATE_2, "RUN-1", List.of(CLOSED_PARENT, unitGoal("X", "S"), stayingInSouth), keyResults);

        Summary north = assembler.assemble(current, base, NORTH, TREE);
        Summary south = assembler.assemble(current, base, SOUTH, TREE);
        Summary department = assembler.assemble(current, base, DEP, TREE);

        assertThat(north.metrics()).containsExactly(
                share(null, "0.0", null, null),
                total("0", "1", "-1", Trend.BETTER),
                coverage(null, "100.0", null, null));
        // В N пара не попадает ни в один счётчик, хотя прошлое значение там было 1.
        assertThat(north.delta()).isEqualTo(new DeltaCounts(0, 0, 0));

        assertThat(south.metrics()).containsExactly(
                share("50.0", "100.0", "-50.0", Trend.WORSE),
                total("1", "0", "1", Trend.WORSE),
                coverage("100.0", "100.0", "0.0", Trend.SAME));
        assertThat(south.delta()).isEqualTo(new DeltaCounts(0, 1, 0));

        assertThat(department.metrics()).containsExactly(
                share("50.0", "50.0", "0.0", Trend.SAME),
                total("1", "1", "0", Trend.SAME),
                coverage("100.0", "100.0", "0.0", Trend.SAME));
        assertThat(department.delta()).isEqualTo(new DeltaCounts(0, 1, 0));
    }

    @Test
    void goalsOfGrandchildAreCountedInDepartment() {
        Unit grandchild = new Unit("N-1", "Отдел", "N");
        UnitTree tree = new UnitTree(List.of(DEP, NORTH, grandchild));
        RunData run = completedRun("RUN-1", DATE_1, null, List.of(CLOSED_PARENT, unitGoal("G", "N-1")), List.of());

        Summary summary = assembler.assemble(run, null, DEP, tree);

        assertThat(summary.metrics()).containsExactly(
                share("0.0", null, null, null),
                total("1", null, null, null),
                coverage("100.0", null, null, null));
        assertThat(summary.issuesBySeverity()).containsExactly(
                new SeverityCount(Severity.CRITICAL, 1, null, null),
                new SeverityCount(Severity.MAJOR, 0, null, null),
                new SeverityCount(Severity.MINOR, 0, null, null));
    }

    @Test
    void ownActiveGoalOfParentIsCountedInItsSummaryAndNotInChild() {
        // У департамента своя активная цель без КР, а C в N чистая.
        GoalRow own = activeGoal("OWN", "DEP", GoalLevel.DEPARTMENT, null);
        RunData run = completedRun(
                "RUN-1", DATE_1, null,
                List.of(CLOSED_PARENT, own, C),
                List.of(keyResult("KR-C", "C", "100.00", FRESH_FACT)));

        Summary department = assembler.assemble(run, null, DEP, TREE);
        Summary north = assembler.assemble(run, null, NORTH, TREE);

        assertThat(department.metrics()).containsExactly(
                share("50.0", null, null, null),
                total("1", null, null, null),
                coverage("100.0", null, null, null));
        assertThat(department.issuesBySeverity().get(0)).isEqualTo(new SeverityCount(Severity.CRITICAL, 1, null, null));
        assertThat(north.metrics()).containsExactly(
                share("100.0", null, null, null),
                total("0", null, null, null),
                coverage("100.0", null, null, null));
    }

    private static GoalRow unitGoal(String id, String unitId) {
        return activeGoal(id, unitId, GoalLevel.UNIT, "TOP");
    }

    private static RunData completedRun(
            String id, LocalDate date, String comparedToRunId, List<GoalRow> goals, List<KeyResultRow> keyResults) {
        Run run = new Run(id, date, RunStatus.COMPLETED, comparedToRunId, null);
        // Проблемы считает настоящий движок по полному снимку, а сводке нужны только его цели.
        Snapshot snapshot = new Snapshot(date, goals, keyResults);
        return new RunData(run, snapshot.goals(), RULE_ENGINE.check(snapshot, CATALOG));
    }

    private static MetricComparison share(String value, String previous, String delta, Trend trend) {
        return metric(MetricKind.CLEAN_GOALS_SHARE, value, previous, delta, trend);
    }

    private static MetricComparison total(String value, String previous, String delta, Trend trend) {
        return metric(MetricKind.ISSUES_TOTAL, value, previous, delta, trend);
    }

    private static MetricComparison coverage(String value, String previous, String delta, Trend trend) {
        return metric(MetricKind.CASCADE_COVERAGE, value, previous, delta, trend);
    }

    private static MetricComparison metric(
            MetricKind kind, String value, String previous, String delta, Trend trend) {
        return new MetricComparison(kind, decimal(value), decimal(previous), decimal(delta), trend);
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }
}
