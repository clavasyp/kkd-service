package ru.kkd.domain.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Snapshot;

public class RuleEngine {

    private static final List<Rule> RULES = List.of(
            new GoalNoKrRule(),
            new KrWeightsSumRule(),
            new CascadeNoParentRule(),
            new KrStaleProgressRule());

    // Порядок раздела 8.4. Код сравниваем как текст: порядок констант в RuleCode другой.
    // object_id по байтам UTF-8, как COLLATE "C" в базе: String.compareTo расходится с ним на символах вне BMP.
    private static final Comparator<Issue> ISSUE_ORDER = Comparator
            .comparing(Issue::severity)
            .thenComparing(issue -> issue.ruleCode().name())
            .thenComparing(issue -> issue.objectId().getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned);

    public List<Issue> check(Snapshot snapshot, RuleCatalog catalog) {
        List<GoalRow> activeGoals = snapshot.goals().stream()
                .filter(GoalRow::isActive)
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
