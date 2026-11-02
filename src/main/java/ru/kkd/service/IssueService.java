package ru.kkd.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.org.UnitTree;
import ru.kkd.repository.IssueFilter;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;
import ru.kkd.repository.UnitRepository;

@Service
public class IssueService {

    private final RunRepository runs;
    private final UnitRepository units;
    private final SnapshotRepository snapshots;
    private final IssueRepository issues;

    public IssueService(
            RunRepository runs, UnitRepository units, SnapshotRepository snapshots, IssueRepository issues) {
        this.runs = runs;
        this.units = units;
        this.snapshots = snapshots;
        this.issues = issues;
    }

    /** Порядок проверок: прогон, подразделение, состояние прогона. Раздел 8.1 design.md. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IssuePage findIssues(IssueQuery query) {
        Run run = runs.findById(query.runId()).orElseThrow(() -> KkdException.runNotFound(query.runId()));
        Set<String> unitIds = query.unitId() == null ? null : branchOf(query.unitId());
        CompletedRun.require(run);

        IssueFilter filter = new IssueFilter(run.id(), unitIds, query.ruleCode(), query.severity());
        List<Issue> page = issues.findPage(filter, query.limit(), query.offset());
        // Названия только целей этой страницы, а не всего снимка.
        Map<String, String> goalTitles = snapshots.findGoalTitles(
                run.id(), page.stream().map(Issue::goalId).collect(Collectors.toSet()));
        List<IssuePage.Item> items = page.stream()
                .map(issue -> new IssuePage.Item(issue, goalTitles.get(issue.goalId())))
                .toList();
        return new IssuePage(run.id(), items, issues.count(filter), query.limit(), query.offset());
    }

    private Set<String> branchOf(String unitId) {
        units.findById(unitId).orElseThrow(() -> KkdException.unitNotFound(unitId));
        return new UnitTree(units.findAll()).branchOf(unitId);
    }
}
