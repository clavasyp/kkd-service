package ru.kkd.domain.delta;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.kkd.domain.model.Issue;

public class DeltaCalculator {

    /**
     * Принимает все проблемы обоих прогонов, а не только проблемы ветки: статус считается по всей организации,
     * иначе проблема цели, перенесённой в другое подразделение, стала бы новой.
     * baseIssues равен null, если сравнивать не с чем. Пустой список значит, что база есть, а проблем в ней нет.
     */
    public DeltaCounts calculate(List<Issue> currentIssues, List<Issue> baseIssues, Set<String> branchUnitIds) {
        if (baseIssues == null) {
            return new DeltaCounts(null, null, null);
        }

        Set<IssueKey> currentKeys = keysOf(currentIssues);
        Set<IssueKey> baseKeys = keysOf(baseIssues);

        int newCount = 0;
        int persisting = 0;
        for (Issue issue : currentIssues) {
            if (branchUnitIds.contains(issue.unitId())) {
                if (baseKeys.contains(IssueKey.of(issue))) {
                    persisting++;
                } else {
                    newCount++;
                }
            }
        }

        // Исправленной проблемы в текущем прогоне нет, поэтому к ветке она относится по прошлой строке.
        int resolved = 0;
        for (Issue issue : baseIssues) {
            if (branchUnitIds.contains(issue.unitId()) && !currentKeys.contains(IssueKey.of(issue))) {
                resolved++;
            }
        }

        return new DeltaCounts(newCount, persisting, resolved);
    }

    private Set<IssueKey> keysOf(List<Issue> issues) {
        Set<IssueKey> keys = new HashSet<>();
        for (Issue issue : issues) {
            keys.add(IssueKey.of(issue));
        }
        return keys;
    }
}
