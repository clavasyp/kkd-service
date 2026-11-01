package ru.kkd.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Временная заготовка этапа 0: проверяет snake_case, null и поле new в JSON. */
public record ProbeResponse(long id, String displayName, BigDecimal previous, Counters delta) {

    public record Counters(@JsonProperty("new") int newCount) {
    }
}
