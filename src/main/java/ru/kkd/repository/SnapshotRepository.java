package ru.kkd.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.Snapshot;

@Repository
public class SnapshotRepository {

    private static final String EXISTS = """
            SELECT EXISTS (SELECT 1 FROM goal_snapshot WHERE run_id = ?)
            """;

    // Те же запросы, что в 004-seed.sql: снимаются все цели и КР, включая закрытые.
    private static final String FREEZE_GOALS = """
            INSERT INTO goal_snapshot (run_id, goal_id, title, unit_id, level, owner_name, parent_goal_id, status, due_date)
            SELECT ?, id, title, unit_id, level, owner_name, parent_goal_id, status, due_date FROM goal
            """;

    private static final String FREEZE_KEY_RESULTS = """
            INSERT INTO key_result_snapshot (run_id, kr_id, goal_id, title, measure_unit, target_value, fact_value, weight, fact_updated_on)
            SELECT ?, id, goal_id, title, measure_unit, target_value, fact_value, weight, fact_updated_on FROM key_result
            """;

    private static final String LOAD_GOALS = """
            SELECT goal_id, title, unit_id, level, owner_name, parent_goal_id, status, due_date
            FROM goal_snapshot
            WHERE run_id = ?
            """;

    private static final String LOAD_KEY_RESULTS = """
            SELECT kr_id, goal_id, title, measure_unit, target_value, fact_value, weight, fact_updated_on
            FROM key_result_snapshot
            WHERE run_id = ?
            """;

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Строка цели есть в любом непустом снимке: КР без цели снимка запрещает FK. */
    public boolean exists(String runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS, Boolean.class, runId));
    }

    public void freeze(String runId) {
        jdbc.update(FREEZE_GOALS, runId);
        jdbc.update(FREEZE_KEY_RESULTS, runId);
    }

    public Snapshot load(String runId, LocalDate snapshotDate) {
        List<GoalRow> goals = jdbc.query(LOAD_GOALS, SnapshotRepository::mapGoal, runId);
        List<KeyResultRow> keyResults = jdbc.query(LOAD_KEY_RESULTS, SnapshotRepository::mapKeyResult, runId);
        return new Snapshot(snapshotDate, goals, keyResults);
    }

    private static GoalRow mapGoal(ResultSet rs, int rowNum) throws SQLException {
        return new GoalRow(
                rs.getString("goal_id"),
                rs.getString("title"),
                rs.getString("unit_id"),
                EnumText.parse(GoalLevel.class, rs.getString("level")),
                rs.getString("owner_name"),
                rs.getString("parent_goal_id"),
                rs.getString("status"),
                rs.getObject("due_date", LocalDate.class));
    }

    private static KeyResultRow mapKeyResult(ResultSet rs, int rowNum) throws SQLException {
        return new KeyResultRow(
                rs.getString("kr_id"),
                rs.getString("goal_id"),
                rs.getString("title"),
                rs.getString("measure_unit"),
                rs.getBigDecimal("target_value"),
                rs.getBigDecimal("fact_value"),
                rs.getBigDecimal("weight"),
                rs.getObject("fact_updated_on", LocalDate.class));
    }
}
