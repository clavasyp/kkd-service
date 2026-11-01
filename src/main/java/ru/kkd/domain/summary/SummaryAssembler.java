package ru.kkd.domain.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.kkd.domain.delta.DeltaCalculator;
import ru.kkd.domain.metrics.MetricCalculator;
import ru.kkd.domain.metrics.MetricComparison;
import ru.kkd.domain.metrics.MetricKind;
import ru.kkd.domain.metrics.MetricValues;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.org.UnitTree;

public class SummaryAssembler {

    private final MetricCalculator metricCalculator = new MetricCalculator();
    private final DeltaCalculator deltaCalculator = new DeltaCalculator();

    /** base равен null, если сравнивать не с чем: это первый прогон или все прошлые упали. */
    public Summary assemble(RunData current, RunData base, Unit unit, UnitTree tree) {
        Set<String> branch = tree.branchOf(unit.id());
        return new Summary(
                current.run(),
                base == null ? null : base.run(),
                unit,
                metrics(current, base, branch),
                issuesBySeverity(current, base, branch),
                deltaCalculator.calculate(current.issues(), base == null ? null : base.issues(), branch));
    }

    private List<MetricComparison> metrics(RunData current, RunData base, Set<String> branch) {
        MetricValues now = valuesOf(current, branch);
        // Без базы все прошлые значения равны null. Счётчик тоже: 0 значил бы, что прошлый прогон ничего не нашёл.
        MetricValues before = base == null ? new MetricValues(null, null, null) : valuesOf(base, branch);
        return List.of(
                MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, now.cleanGoalsShare(), before.cleanGoalsShare()),
                MetricComparison.of(MetricKind.ISSUES_TOTAL, now.issuesTotal(), before.issuesTotal()),
                MetricComparison.of(MetricKind.CASCADE_COVERAGE, now.cascadeCoverage(), before.cascadeCoverage()));
    }

    private MetricValues valuesOf(RunData data, Set<String> branch) {
        return metricCalculator.calculate(data.snapshot().goals(), data.issues(), branch);
    }

    private List<SeverityCount> issuesBySeverity(RunData current, RunData base, Set<String> branch) {
        List<SeverityCount> rows = new ArrayList<>();
        for (Severity severity : Severity.values()) {
            int now = count(current.issues(), severity, branch);
            if (base == null) {
                rows.add(new SeverityCount(severity, now, null, null));
            } else {
                int before = count(base.issues(), severity, branch);
                rows.add(new SeverityCount(severity, now, before, now - before));
            }
        }
        return rows;
    }

    private int count(List<Issue> issues, Severity severity, Set<String> branch) {
        int count = 0;
        for (Issue issue : issues) {
            if (issue.severity() == severity && branch.contains(issue.unitId())) {
                count++;
            }
        }
        return count;
    }
}
