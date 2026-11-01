package ru.kkd.domain.rules;

import java.util.ArrayList;
import java.util.List;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

public class CascadeNoParentRule implements Rule {

    @Override
    public RuleCode code() {
        return RuleCode.CASCADE_NO_PARENT;
    }

    @Override
    public List<Issue> check(Snapshot snapshot, List<GoalRow> activeGoals, Severity severity) {
        List<Issue> issues = new ArrayList<>();
        for (GoalRow goal : activeGoals) {
            String problem = findProblem(goal, snapshot);
            if (problem != null) {
                issues.add(new Issue(
                        code(),
                        goal.id(),
                        Issue.OBJECT_TYPE_GOAL,
                        severity,
                        goal.id(),
                        goal.unitId(),
                        problem));
            }
        }
        return issues;
    }

    /** Возвращает причину нарушения или null, если нарушения нет. */
    private String findProblem(GoalRow goal, Snapshot snapshot) {
        // У верхнего уровня вышестоящей цели нет, поэтому его родитель не проверяется вообще.
        if (goal.level() == GoalLevel.DEPARTMENT) {
            return null;
        }
        if (goal.parentGoalId() == null) {
            return "Родительская цель не указана";
        }
        GoalRow parent = findGoal(goal.parentGoalId(), snapshot);
        if (parent == null) {
            return "Родительская цель " + goal.parentGoalId() + " не найдена";
        }
        if (!parent.level().isHigherThan(goal.level())) {
            return "Родительская цель " + parent.id() + " не выше по уровню";
        }
        return null;
    }

    // Ищем среди всех целей снимка: закрытый родитель тоже считается связью.
    private GoalRow findGoal(String goalId, Snapshot snapshot) {
        for (GoalRow goal : snapshot.goals()) {
            if (goal.id().equals(goalId)) {
                return goal;
            }
        }
        return null;
    }
}
