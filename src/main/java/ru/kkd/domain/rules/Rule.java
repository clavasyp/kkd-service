package ru.kkd.domain.rules;

import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

public interface Rule {

    RuleCode code();

    /**
     * Активные цели отбирает RuleEngine. Снимок передаётся целиком: в нём КР, дата отсчёта
     * и закрытые цели, которые могут быть родителями.
     */
    List<Issue> check(Snapshot snapshot, List<GoalRow> activeGoals, Severity severity);
}
