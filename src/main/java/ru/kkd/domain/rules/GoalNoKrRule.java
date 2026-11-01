package ru.kkd.domain.rules;

import java.util.ArrayList;
import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

public class GoalNoKrRule implements Rule {

    @Override
    public RuleCode code() {
        return RuleCode.GOAL_NO_KR;
    }

    @Override
    public List<Issue> check(Snapshot snapshot, List<GoalRow> activeGoals, Severity severity) {
        List<Issue> issues = new ArrayList<>();
        for (GoalRow goal : activeGoals) {
            if (snapshot.keyResultsOf(goal.id()).isEmpty()) {
                issues.add(new Issue(
                        code(),
                        goal.id(),
                        Issue.OBJECT_TYPE_GOAL,
                        severity,
                        goal.id(),
                        goal.unitId(),
                        "У активной цели нет ни одного ключевого результата"));
            }
        }
        return issues;
    }
}
