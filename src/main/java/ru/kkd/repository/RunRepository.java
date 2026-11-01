package ru.kkd.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunListItem;
import ru.kkd.domain.model.RunStatus;

@Repository
public class RunRepository {

    private static final String FIND_ALL = """
            SELECT id, snapshot_date, status, compared_to_run_id, error
            FROM kkd_run
            """;

    private static final String FIND_NEXT_RUNNING = """
            SELECT id, snapshot_date, status, compared_to_run_id, error
            FROM kkd_run
            WHERE status = 'running'
            ORDER BY snapshot_date, id
            LIMIT 1
            """;

    // Условие на статус: completed и failed конечны, раздел 6.1 design.md.
    private static final String MARK_COMPLETED = """
            UPDATE kkd_run
            SET status = 'completed', compared_to_run_id = ?
            WHERE id = ? AND status = 'running'
            """;

    private static final String MARK_FAILED = """
            UPDATE kkd_run
            SET status = 'failed', error = ?
            WHERE id = ? AND status = 'running'
            """;

    private static final String FIND_ALL_WITH_ISSUE_COUNTS = """
            SELECT r.id, r.snapshot_date, r.status, r.compared_to_run_id, r.error, COUNT(i.run_id) AS issues_total
            FROM kkd_run r
            LEFT JOIN kkd_issue i ON i.run_id = r.id
            GROUP BY r.id
            ORDER BY r.snapshot_date DESC, r.id DESC
            """;

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Run> findAll() {
        return jdbc.query(FIND_ALL, RunRepository::mapRun);
    }

    /** Самый ранний прогон running по (snapshot_date, id). */
    public Optional<Run> findNextRunning() {
        return jdbc.query(FIND_NEXT_RUNNING, RunRepository::mapRun).stream().findFirst();
    }

    public void markCompleted(String runId, String comparedToRunId) {
        jdbc.update(MARK_COMPLETED, comparedToRunId, runId);
    }

    public void markFailed(String runId, String error) {
        jdbc.update(MARK_FAILED, error, runId);
    }

    /** Новые сверху: snapshot_date DESC, id DESC. */
    public List<RunListItem> findAllWithIssueCounts() {
        return jdbc.query(
                FIND_ALL_WITH_ISSUE_COUNTS,
                (rs, rowNum) -> new RunListItem(mapRun(rs, rowNum), rs.getInt("issues_total")));
    }

    private static Run mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new Run(
                rs.getString("id"),
                rs.getObject("snapshot_date", LocalDate.class),
                EnumText.parse(RunStatus.class, rs.getString("status")),
                rs.getString("compared_to_run_id"),
                rs.getString("error"));
    }
}
