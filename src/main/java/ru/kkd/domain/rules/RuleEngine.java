package ru.kkd.domain.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Snapshot;

public class RuleEngine {

    private static final String ACTIVE_STATUS = "active";

    private static final List<Rule> RULES = List.of(
            new GoalNoKrRule(),
            new KrWeightsSumRule(),
            new CascadeNoParentRule(),
            new KrStaleProgressRule());

    // Порядок раздела 8.4. Код сравниваем как текст: порядок констант в RuleCode другой.
    private static final Comparator<Issue> ISSUE_ORDER = Comparator
            .comparing(Issue::severity)
            .thenComparing(issue -> issue.ruleCode().name())
            .thenComparing(Issue::objectId);

    public List<Issue> check(Snapshot snapshot, RuleCatalog catalog) {
        List<GoalRow> activeGoals = snapshot.goals().stream()
                .filter(goal -> ACTIVE_STATUS.equals(goal.status()))
                .toList();

        List<Issue> issues = new ArrayList<>();
        for (Rule rule : RULES) {
            if (catalog.isEnabled(rule.code())) {
                issues.addAll(rule.check(snapshot, activeGoals, catalog.severityOf(rule.code())));
            }
        }
        issues.sort(ISSUE_ORDER);
        return issues;
    }
}
