package ru.kkd.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Issue;

@Repository
public class IssueRepository {

    private static final String INSERT = """
            INSERT INTO kkd_issue (run_id, rule_code, object_id, object_type, severity, goal_id, unit_id, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public IssueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAll(String runId, List<Issue> issues) {
        List<Object[]> rows = issues.stream()
                .map(issue -> new Object[] {
                    runId,
                    issue.ruleCode().name(),
                    issue.objectId(),
                    issue.objectType(),
                    EnumText.format(issue.severity()),
                    issue.goalId(),
                    issue.unitId(),
                    issue.details()
                })
                .toList();
        jdbc.batchUpdate(INSERT, rows);
    }
}
