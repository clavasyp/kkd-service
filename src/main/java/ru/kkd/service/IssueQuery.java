package ru.kkd.service;

import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

/** Разобранные параметры GET /api/kkd/issues. Могут быть null: unitId, ruleCode, severity. */
public record IssueQuery(String runId, String unitId, RuleCode ruleCode, Severity severity, int limit, int offset) {
}
