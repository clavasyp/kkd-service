package ru.kkd.domain.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.kkd.domain.TestData.activeGoal;
import static ru.kkd.domain.TestData.goal;
import static ru.kkd.domain.TestData.noParentIssue;
import static ru.kkd.domain.TestData.staleIssue;
import static ru.kkd.domain.TestData.weightsSumIssue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;

// isEqualTo у BigDecimal сравнивает и масштаб: у доли один знак после точки, у счётчика ни одного.
class MetricCalculatorTest {

    private static final String DEP = "DEP";
    private static final String NORTH = "N";
    private static final String SOUTH = "S";
    private static final Set<String> NORTH_BRANCH = Set.of(NORTH);
    private static final Set<String> DEP_BRANCH = Set.of(DEP, NORTH, SOUTH);

    private final MetricCalculator calculator = new MetricCalculator();

    @Test
    void keyResultIssueMakesGoalIncorrect() {
        List<GoalRow> goals = List.of(unitGoal("G-1", NORTH), unitGoal("G-2", NORTH));
        List<Issue> issues = List.of(staleIssue("KR-1", "G-1", NORTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("50.0"));
    }

    @Test
    void threeIssuesOfOneGoalCountAsOneGoalButThreeRows() {
        List<GoalRow> goals = List.of(unitGoal("G-1", NORTH), unitGoal("G-2", NORTH));
        // Объекты у всех трёх разные, а цель одна: считать надо по goalId, а не по objectId.
        List<Issue> issues = List.of(
                weightsSumIssue("G-1", NORTH),
                staleIssue("KR-1", "G-1", NORTH),
                staleIssue("KR-2", "G-1", NORTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("50.0"));
        assertThat(values.issuesTotal()).isEqualTo(new BigDecimal("3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "cancelled", "ACTIVE"})
    void inactiveGoalIsNotInDenominators(String status) {
        List<GoalRow> goals = List.of(
                unitGoal("G-1", NORTH),
                unitGoal("G-2", NORTH),
                goal("G-3", NORTH, GoalLevel.UNIT, "G-TOP", status));
        List<Issue> issues = List.of(noParentIssue("G-2", NORTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        // С неактивной целью в знаменателе вышло бы 66.7.
        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("50.0"));
        assertThat(values.cascadeCoverage()).isEqualTo(new BigDecimal("50.0"));
    }

    @Test
    void noGoalsGiveNullSharesAndZeroCount() {
        MetricValues values = calculator.calculate(List.of(), List.of(), NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isNull();
        assertThat(values.cascadeCoverage()).isNull();
        assertThat(values.issuesTotal()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void branchWithoutActiveGoalsGivesNullSharesAndZeroCount() {
        List<GoalRow> goals = List.of(
                goal("G-1", NORTH, GoalLevel.UNIT, "G-TOP", "closed"),
                unitGoal("G-2", SOUTH));
        List<Issue> issues = List.of(weightsSumIssue("G-2", SOUTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isNull();
        assertThat(values.cascadeCoverage()).isNull();
        assertThat(values.issuesTotal()).isEqualTo(BigDecimal.ZERO);
    }

    // 6.25 это ровно половина: округление к чётному дало бы 6.2. У 1 из 22 округление через 4.55 дало бы 4.6.
    @ParameterizedTest(name = "{0} из {1} это {2}")
    @CsvSource({
        "1, 16, 6.3",
        "2, 3, 66.7",
        "1, 3, 33.3",
        "1, 22, 4.5",
        "3, 3, 100.0",
        "0, 3, 0.0"
    })
    void sharesAreRoundedHalfUpToOneDecimal(int clean, int total, BigDecimal expected) {
        List<GoalRow> goals = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            String goalId = "G-" + i;
            goals.add(unitGoal(goalId, NORTH));
            if (i > clean) {
                issues.add(noParentIssue(goalId, NORTH));
            }
        }

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(expected);
        assertThat(values.cascadeCoverage()).isEqualTo(expected);
    }

    @Test
    void parentUsesCommonCountAndNotAverageOfChildren() {
        // В N чиста 1 цель из 1, в S 1 из 2. Среднее долей дало бы 75.0.
        List<GoalRow> goals = List.of(
                unitGoal("G-1", NORTH),
                unitGoal("G-2", SOUTH),
                unitGoal("G-3", SOUTH));
        List<Issue> issues = List.of(noParentIssue("G-3", SOUTH));

        MetricValues values = calculator.calculate(goals, issues, DEP_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("66.7"));
        assertThat(values.cascadeCoverage()).isEqualTo(new BigDecimal("66.7"));
        assertThat(values.issuesTotal()).isEqualTo(new BigDecimal("1"));
    }

    @Test
    void ownGoalsOfParentAreCounted() {
        List<GoalRow> goals = List.of(unitGoal("G-1", DEP), unitGoal("G-2", NORTH));
        List<Issue> issues = List.of(weightsSumIssue("G-1", DEP));

        MetricValues values = calculator.calculate(goals, issues, DEP_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("50.0"));
        assertThat(values.issuesTotal()).isEqualTo(new BigDecimal("1"));
    }

    @Test
    void goalsAndIssuesOfAnotherBranchAreNotCounted() {
        List<GoalRow> goals = List.of(unitGoal("G-1", NORTH), unitGoal("G-2", SOUTH));
        List<Issue> issues = List.of(noParentIssue("G-2", SOUTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("100.0"));
        assertThat(values.cascadeCoverage()).isEqualTo(new BigDecimal("100.0"));
        assertThat(values.issuesTotal()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void coverageIsCountedWithoutTopLevelGoals() {
        List<GoalRow> goals = List.of(
                activeGoal("G-1", DEP, GoalLevel.DEPARTMENT, null),
                activeGoal("G-2", DEP, GoalLevel.DEPARTMENT, null),
                activeGoal("G-3", NORTH, GoalLevel.UNIT, "G-1"),
                activeGoal("G-4", NORTH, GoalLevel.EMPLOYEE, "G-3"),
                activeGoal("G-5", NORTH, GoalLevel.EMPLOYEE, null));
        List<Issue> issues = List.of(noParentIssue("G-5", NORTH));

        MetricValues values = calculator.calculate(goals, issues, DEP_BRANCH);

        // В покрытии 2 из 3 целей ниже верхнего уровня, а в доле участвуют все 5 активных.
        assertThat(values.cascadeCoverage()).isEqualTo(new BigDecimal("66.7"));
        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("80.0"));
    }

    @Test
    void onlyTopLevelActiveGoalsGiveNullCoverageAndFilledShare() {
        List<GoalRow> goals = List.of(
                activeGoal("G-1", DEP, GoalLevel.DEPARTMENT, null),
                goal("G-2", DEP, GoalLevel.UNIT, "G-1", "closed"));

        MetricValues values = calculator.calculate(goals, List.of(), DEP_BRANCH);

        assertThat(values.cascadeCoverage()).isNull();
        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("100.0"));
    }

    @Test
    void onlyCascadeIssuesLowerCoverage() {
        List<GoalRow> goals = List.of(unitGoal("G-1", NORTH), unitGoal("G-2", NORTH));
        List<Issue> issues = List.of(weightsSumIssue("G-1", NORTH), staleIssue("KR-2", "G-2", NORTH));

        MetricValues values = calculator.calculate(goals, issues, NORTH_BRANCH);

        assertThat(values.cascadeCoverage()).isEqualTo(new BigDecimal("100.0"));
        assertThat(values.cleanGoalsShare()).isEqualTo(new BigDecimal("0.0"));
    }

    private static GoalRow unitGoal(String id, String unitId) {
        return activeGoal(id, unitId, GoalLevel.UNIT, "G-TOP");
    }
}
