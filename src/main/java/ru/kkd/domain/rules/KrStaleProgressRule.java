package ru.kkd.domain.rules;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

public class KrStaleProgressRule implements Rule {

    private static final long MAX_AGE_DAYS = 45;

    @Override
    public RuleCode code() {
        return RuleCode.KR_STALE_PROGRESS;
    }

    @Override
    public List<Issue> check(Snapshot snapshot, List<GoalRow> activeGoals, Severity severity) {
        List<Issue> issues = new ArrayList<>();
        for (GoalRow goal : activeGoals) {
            for (KeyResultRow keyResult : snapshot.keyResultsOf(goal.id())) {
                // Пустая дата: возраст неизвестен, это не нарушение.
                if (keyResult.factUpdatedOn() == null) {
                    continue;
                }
                long ageDays = ChronoUnit.DAYS.between(keyResult.factUpdatedOn(), snapshot.snapshotDate());
                if (ageDays > MAX_AGE_DAYS) {
                    issues.add(new Issue(
                            code(),
                            keyResult.id(),
                            Issue.OBJECT_TYPE_KEY_RESULT,
                            severity,
                            goal.id(),
                            goal.unitId(),
                            "Факт не обновлялся " + ageDays + " дн."));
                }
            }
        }
        return issues;
    }
}
