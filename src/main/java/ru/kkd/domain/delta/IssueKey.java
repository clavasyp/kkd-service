package ru.kkd.domain.delta;

import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;

/** Проблема та же самая, если совпали правило и объект. Текст details в ключ не входит. */
public record IssueKey(RuleCode ruleCode, String objectId) {

    public static IssueKey of(Issue issue) {
        return new IssueKey(issue.ruleCode(), issue.objectId());
    }
}
