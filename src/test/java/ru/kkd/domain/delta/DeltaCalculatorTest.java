package ru.kkd.domain.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.kkd.domain.TestData.goalIssue;
import static ru.kkd.domain.TestData.noKrIssue;
import static ru.kkd.domain.TestData.noParentIssue;
import static ru.kkd.domain.TestData.staleIssue;
import static ru.kkd.domain.TestData.weightsSumIssue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

class DeltaCalculatorTest {

    private static final String DEP = "DEP";
    private static final String NORTH = "N";
    private static final String SOUTH = "S";
    private static final Set<String> NORTH_BRANCH = Set.of(NORTH);
    private static final Set<String> SOUTH_BRANCH = Set.of(SOUTH);
    private static final Set<String> DEP_BRANCH = Set.of(DEP, NORTH, SOUTH);

    private final DeltaCalculator calculator = new DeltaCalculator();

    @Test
    void wasXYZNowXWGivesOneNewOnePersistingTwoResolved() {
        Issue x = weightsSumIssue("G-X", NORTH);
        Issue y = weightsSumIssue("G-Y", NORTH);
        Issue z = noKrIssue("G-Z", NORTH);
        Issue w = staleIssue("KR-W", "G-W", NORTH);

        DeltaCounts counts = calculator.calculate(List.of(x, w), List.of(x, y, z), NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(1, 1, 2));
    }

    @Test
    void sameRuleOnAnotherObjectIsAnotherIssue() {
        DeltaCounts counts = calculator.calculate(
                List.of(weightsSumIssue("G-2", NORTH)), List.of(weightsSumIssue("G-1", NORTH)), NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(1, 0, 1));
    }

    @Test
    void anotherRuleOnSameObjectIsAnotherIssue() {
        DeltaCounts counts = calculator.calculate(
                List.of(weightsSumIssue("G-1", NORTH)), List.of(noKrIssue("G-1", NORTH)), NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(1, 0, 1));
    }

    @Test
    void anotherTextOfSamePairIsPersisting() {
        Issue before = goalIssue(
                RuleCode.KR_WEIGHTS_SUM, "G-1", NORTH, Severity.MAJOR, "Сумма весов КР равна 90, а должна быть 100");
        Issue after = goalIssue(
                RuleCode.KR_WEIGHTS_SUM, "G-1", NORTH, Severity.MAJOR, "Сумма весов КР равна 85, а должна быть 100");

        DeltaCounts counts = calculator.calculate(List.of(after), List.of(before), NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(0, 1, 0));
    }

    @Test
    void anotherSeverityOfSamePairIsPersisting() {
        Issue before = goalIssue(RuleCode.KR_WEIGHTS_SUM, "G-1", NORTH, Severity.MAJOR, "Текст");
        Issue after = goalIssue(RuleCode.KR_WEIGHTS_SUM, "G-1", NORTH, Severity.MINOR, "Текст");

        DeltaCounts counts = calculator.calculate(List.of(after), List.of(before), NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(0, 1, 0));
    }

    @Test
    void keyResultIssueIsIdentifiedByKeyResultAndNotByGoal() {
        Issue before = staleIssue("KR-1", "G-1", NORTH);
        Issue movedToAnotherGoal = staleIssue("KR-1", "G-2", NORTH);
        Issue anotherKeyResultOfSameGoal = staleIssue("KR-2", "G-1", NORTH);

        assertThat(calculator.calculate(List.of(movedToAnotherGoal), List.of(before), NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(0, 1, 0));
        assertThat(calculator.calculate(List.of(anotherKeyResultOfSameGoal), List.of(before), NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(1, 0, 1));
    }

    @Test
    void closedGoalGivesResolved() {
        // Цель закрыли, правила её больше не проверяют, поэтому пары нет в текущем прогоне.
        List<Issue> base = List.of(staleIssue("KR-14-1", "G-14", NORTH));

        DeltaCounts counts = calculator.calculate(List.of(), base, NORTH_BRANCH);

        assertThat(counts).isEqualTo(new DeltaCounts(0, 0, 1));
    }

    @Test
    void noBaseGivesThreeNullsAndNotZeros() {
        List<Issue> current = List.of(weightsSumIssue("G-1", NORTH));

        DeltaCounts counts = calculator.calculate(current, null, NORTH_BRANCH);

        assertThat(counts.newCount()).isNull();
        assertThat(counts.persisting()).isNull();
        assertThat(counts.resolved()).isNull();
    }

    @Test
    void noBaseGivesThreeNullsEvenForEmptyCurrentRun() {
        assertThat(calculator.calculate(List.of(), null, NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(null, null, null));
    }

    @Test
    void emptyBaseIsStillBaseAndGivesNumbers() {
        assertThat(calculator.calculate(List.of(weightsSumIssue("G-1", NORTH)), List.of(), NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(1, 0, 0));
        assertThat(calculator.calculate(List.of(), List.of(), NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(0, 0, 0));
    }

    @Test
    void issueThatDisappearedAndCameBackIsNewAgain() {
        List<Issue> withIssue = List.of(weightsSumIssue("G-1", NORTH));
        List<Issue> withoutIssue = List.of();

        assertThat(calculator.calculate(withoutIssue, withIssue, NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(0, 0, 1));
        assertThat(calculator.calculate(withIssue, withoutIssue, NORTH_BRANCH))
                .isEqualTo(new DeltaCounts(1, 0, 0));
    }

    @Test
    void movedPairIsPersistingInNewUnitAndParentAndAbsentFromOldUnit() {
        List<Issue> base = List.of(weightsSumIssue("G-1", NORTH));
        List<Issue> current = List.of(weightsSumIssue("G-1", SOUTH));

        assertThat(calculator.calculate(current, base, SOUTH_BRANCH)).isEqualTo(new DeltaCounts(0, 1, 0));
        assertThat(calculator.calculate(current, base, NORTH_BRANCH)).isEqualTo(new DeltaCounts(0, 0, 0));
        assertThat(calculator.calculate(current, base, DEP_BRANCH)).isEqualTo(new DeltaCounts(0, 1, 0));
    }

    @Test
    void movedGoalWithChangedRuleGivesResolvedInOldUnitAndNewInNewUnit() {
        // Цель перешла из N в S, и одновременно старая проблема ушла, а появилась другая.
        List<Issue> base = List.of(weightsSumIssue("G-1", NORTH));
        List<Issue> current = List.of(noParentIssue("G-1", SOUTH));

        assertThat(calculator.calculate(current, base, NORTH_BRANCH)).isEqualTo(new DeltaCounts(0, 0, 1));
        assertThat(calculator.calculate(current, base, SOUTH_BRANCH)).isEqualTo(new DeltaCounts(1, 0, 0));
        assertThat(calculator.calculate(current, base, DEP_BRANCH)).isEqualTo(new DeltaCounts(1, 0, 1));
    }

    @Test
    void newAndPersistingGoToBranchOfCurrentRowAndResolvedToBranchOfPreviousRow() {
        List<Issue> base = List.of(
                weightsSumIssue("G-1", NORTH),
                noKrIssue("G-2", NORTH),
                noKrIssue("G-3", SOUTH));
        List<Issue> current = List.of(
                weightsSumIssue("G-1", NORTH),
                noParentIssue("G-4", NORTH),
                noParentIssue("G-5", SOUTH),
                noParentIssue("G-6", SOUTH));

        assertThat(calculator.calculate(current, base, NORTH_BRANCH)).isEqualTo(new DeltaCounts(1, 1, 1));
        assertThat(calculator.calculate(current, base, SOUTH_BRANCH)).isEqualTo(new DeltaCounts(2, 0, 1));
        assertThat(calculator.calculate(current, base, DEP_BRANCH)).isEqualTo(new DeltaCounts(3, 1, 2));
    }

    @Test
    void branchWithoutIssuesGivesZeros() {
        List<Issue> base = List.of(weightsSumIssue("G-1", NORTH));
        List<Issue> current = List.of(weightsSumIssue("G-1", NORTH), noKrIssue("G-2", SOUTH));

        assertThat(calculator.calculate(current, base, Set.of("EMPTY"))).isEqualTo(new DeltaCounts(0, 0, 0));
    }

    @Test
    void issueKeyIsPairOfRuleAndObject() {
        assertThat(IssueKey.of(staleIssue("KR-1", "G-1", NORTH)))
                .isEqualTo(new IssueKey(RuleCode.KR_STALE_PROGRESS, "KR-1"));
        assertThat(IssueKey.of(weightsSumIssue("G-1", NORTH)))
                .isEqualTo(new IssueKey(RuleCode.KR_WEIGHTS_SUM, "G-1"));
    }
}
