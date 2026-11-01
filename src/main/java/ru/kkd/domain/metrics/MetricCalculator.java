package ru.kkd.domain.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;

public class MetricCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SHARE_SCALE = 1;

    /** Принимает цели снимка и проблемы одного прогона, сам оставляет только ветку. */
    public MetricValues calculate(List<GoalRow> goals, List<Issue> issues, Set<String> branchUnitIds) {
        List<GoalRow> activeGoals = goals.stream()
                .filter(GoalRow::isActive)
                .filter(goal -> branchUnitIds.contains(goal.unitId()))
                .toList();
        List<Issue> branchIssues = issues.stream()
                .filter(issue -> branchUnitIds.contains(issue.unitId()))
                .toList();

        return new MetricValues(
                cleanGoalsShare(activeGoals, branchIssues),
                BigDecimal.valueOf(branchIssues.size()),
                cascadeCoverage(activeGoals, branchIssues));
    }

    private BigDecimal cleanGoalsShare(List<GoalRow> activeGoals, List<Issue> branchIssues) {
        long goalsWithIssues = branchIssues.stream().map(Issue::goalId).distinct().count();
        return percent(activeGoals.size() - goalsWithIssues, activeGoals.size());
    }

    private BigDecimal cascadeCoverage(List<GoalRow> activeGoals, List<Issue> branchIssues) {
        // У верхнего уровня вышестоящей цели нет, поэтому он не входит в знаменатель.
        long checkedGoals = activeGoals.stream()
                .filter(goal -> goal.level() != GoalLevel.DEPARTMENT)
                .count();
        long violations = branchIssues.stream()
                .filter(issue -> issue.ruleCode() == RuleCode.CASCADE_NO_PARENT)
                .count();
        return percent(checkedGoals - violations, checkedGoals);
    }

    // Единственное место округления. Доля от нуля не определена: 0 читался бы как «все цели плохие».
    private BigDecimal percent(long part, long total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
