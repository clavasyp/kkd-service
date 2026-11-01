package ru.kkd.repository;

import java.util.Set;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

/** Условия отбора проблем прогона. null значит «без этого условия»: unitIds, ruleCode, severity. */
public record IssueFilter(String runId, Set<String> unitIds, RuleCode ruleCode, Severity severity) {
}
