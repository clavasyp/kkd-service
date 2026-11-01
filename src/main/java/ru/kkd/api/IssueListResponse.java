package ru.kkd.api;

import java.util.List;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Issue;
import ru.kkd.service.IssuePage;

/** Ответ GET /api/kkd/issues: раздел 8.4 design.md. Порядок полей в JSON задан порядком компонентов записи. */
public record IssueListResponse(String runId, List<Item> items, int total, int limit, int offset) {

    public static IssueListResponse from(IssuePage page) {
        return new IssueListResponse(
                page.runId(),
                page.items().stream().map(Item::from).toList(),
                page.total(),
                page.limit(),
                page.offset());
    }

    public record Item(
            String id,
            String ruleCode,
            String severity,
            String objectType,
            String objectId,
            String goalId,
            String goalTitle,
            String unitId,
            String details) {

        public static Item from(IssuePage.Item item) {
            Issue issue = item.issue();
            String ruleCode = issue.ruleCode().name();
            return new Item(
                    ruleCode + ":" + issue.objectId(),
                    ruleCode,
                    EnumText.format(issue.severity()),
                    issue.objectType(),
                    issue.objectId(),
                    issue.goalId(),
                    item.goalTitle(),
                    issue.unitId(),
                    issue.details());
        }
    }
}
