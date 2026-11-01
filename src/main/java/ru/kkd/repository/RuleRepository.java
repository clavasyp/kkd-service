package ru.kkd.repository;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.rules.RuleCatalog;

@Repository
public class RuleRepository {

    private static final String FIND_ENABLED = """
            SELECT code, severity
            FROM kkd_rule
            WHERE enabled
            """;

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Код правила в базе в верхнем регистре, как и в RuleCode. */
    public RuleCatalog loadCatalog() {
        Map<RuleCode, Severity> severities = new EnumMap<>(RuleCode.class);
        jdbc.query(FIND_ENABLED, rs -> {
            severities.put(
                    RuleCode.valueOf(rs.getString("code")),
                    EnumText.parse(Severity.class, rs.getString("severity")));
        });
        return new RuleCatalog(severities);
    }
}
