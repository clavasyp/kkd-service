package ru.kkd.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Временная заготовка этапа 0: один метод, читающий строку из базы. Удаляется на этапе 4. */
@RestController
public class ProbeController {

    private final JdbcTemplate jdbc;

    public ProbeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/runs")
    public ProbeResponse probe() {
        return jdbc.queryForObject(
                "SELECT id, display_name FROM probe WHERE id = 1",
                (rs, n) -> new ProbeResponse(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        null,
                        new ProbeResponse.Counters(1)));
    }
}
