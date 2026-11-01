package ru.kkd.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.Unit;

@Repository
public class UnitRepository {

    private static final String FIND_ALL = """
            SELECT id, name, parent_id
            FROM unit
            """;

    private static final String FIND_BY_ID = """
            SELECT id, name, parent_id
            FROM unit
            WHERE id = ?
            """;

    private final JdbcTemplate jdbc;

    public UnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Unit> findAll() {
        return jdbc.query(FIND_ALL, UnitRepository::mapUnit);
    }

    public Optional<Unit> findById(String id) {
        return jdbc.query(FIND_BY_ID, UnitRepository::mapUnit, id).stream().findFirst();
    }

    private static Unit mapUnit(ResultSet rs, int rowNum) throws SQLException {
        return new Unit(rs.getString("id"), rs.getString("name"), rs.getString("parent_id"));
    }
}
