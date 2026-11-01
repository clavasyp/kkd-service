package ru.kkd.domain.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

public class KrWeightsSumRule implements Rule {

    private static final BigDecimal REQUIRED_SUM = new BigDecimal("100");

    @Override
    public RuleCode code() {
        return RuleCode.KR_WEIGHTS_SUM;
    }

    @Override
    public List<Issue> check(Snapshot snapshot, List<GoalRow> activeGoals, Severity severity) {
        List<Issue> issues = new ArrayList<>();
        for (GoalRow goal : activeGoals) {
            List<KeyResultRow> keyResults = snapshot.keyResultsOf(goal.id());
            // Цель без КР отмечает GOAL_NO_KR, суммировать здесь нечего.
            if (keyResults.isEmpty()) {
                continue;
            }

            BigDecimal sum = BigDecimal.ZERO;
            boolean hasEmptyWeight = false;
            for (KeyResultRow keyResult : keyResults) {
                if (keyResult.weight() == null) {
                    hasEmptyWeight = true;
                } else {
                    sum = sum.add(keyResult.weight());
                }
            }

            // Пустой вес не считаем за 0: при сумме 100 дефект бы спрятался.
            if (hasEmptyWeight || sum.compareTo(REQUIRED_SUM) != 0) {
                issues.add(new Issue(
                        code(),
                        goal.id(),
                        Issue.OBJECT_TYPE_GOAL,
                        severity,
                        goal.id(),
                        goal.unitId(),
                        details(sum, hasEmptyWeight)));
            }
        }
        return issues;
    }

    private String details(BigDecimal sum, boolean hasEmptyWeight) {
        // Убираем нули, чтобы 90 и 90.00 дали один текст. Без toPlainString число 90 вывелось бы как 9E+1.
        String sumText = sum.stripTrailingZeros().toPlainString();
        if (hasEmptyWeight) {
            return "Сумма заполненных весов КР равна " + sumText + ", есть КР с пустым весом";
        }
        return "Сумма весов КР равна " + sumText + ", а должна быть 100";
    }
}
