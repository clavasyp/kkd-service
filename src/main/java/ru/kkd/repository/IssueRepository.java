package ru.kkd.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

@Repository
public class IssueRepository {

    private static final String INSERT = """
            INSERT INTO kkd_issue (run_id, rule_code, object_id, object_type, severity, goal_id, unit_id, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT rule_code, object_id, object_type, severity, goal_id, unit_id, details
            FROM kkd_issue
            """;

    private static final String COUNT = """
            SELECT COUNT(*)
            FROM kkd_issue
            """;

    // Порядок раздела 8.4 design.md. Сравнение идентификаторов задано в схеме: COLLATE "C".
    private static final String PAGE = """
            ORDER BY
                CASE severity WHEN 'critical' THEN 1 WHEN 'major' THEN 2 WHEN 'minor' THEN 3 END,
                rule_code,
                object_id
            LIMIT :limit OFFSET :offset
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public IssueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
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

    /** Все проблемы прогона в неопределённом порядке: для расчётов, а не для показа. */
    public List<Issue> findByRun(String runId) {
        return jdbc.query(SELECT + "WHERE run_id = ?", IssueRepository::mapIssue, runId);
    }

    public List<Issue> findPage(IssueFilter filter, int limit, int offset) {
        MapSqlParameterSource params = paramsOf(filter).addValue("limit", limit).addValue("offset", offset);
        return namedJdbc.query(SELECT + whereOf(filter) + PAGE, params, IssueRepository::mapIssue);
    }

    public int count(IssueFilter filter) {
        Integer count = namedJdbc.queryForObject(COUNT + whereOf(filter), paramsOf(filter), Integer.class);
        return count == null ? 0 : count;
    }

    private static String whereOf(IssueFilter filter) {
        StringBuilder where = new StringBuilder("WHERE run_id = :run_id\n");
        if (filter.unitIds() != null) {
            where.append("AND unit_id IN (:unit_ids)\n");
        }
        if (filter.ruleCode() != null) {
            where.append("AND rule_code = :rule_code\n");
        }
        if (filter.severity() != null) {
            where.append("AND severity = :severity\n");
        }
        return where.toString();
    }

    private static MapSqlParameterSource paramsOf(IssueFilter filter) {
        return new MapSqlParameterSource()
                .addValue("run_id", filter.runId())
                .addValue("unit_ids", filter.unitIds())
                .addValue("rule_code", filter.ruleCode() == null ? null : filter.ruleCode().name())
                .addValue("severity", filter.severity() == null ? null : EnumText.format(filter.severity()));
    }

    private static Issue mapIssue(ResultSet rs, int rowNum) throws SQLException {
        return new Issue(
                RuleCode.valueOf(rs.getString("rule_code")),
                rs.getString("object_id"),
                rs.getString("object_type"),
                EnumText.parse(Severity.class, rs.getString("severity")),
                rs.getString("goal_id"),
                rs.getString("unit_id"),
                rs.getString("details"));
    }
}
