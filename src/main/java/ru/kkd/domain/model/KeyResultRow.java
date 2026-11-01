package ru.kkd.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** КР на дату снимка. Могут быть null: targetValue, factValue, weight, factUpdatedOn. */
public record KeyResultRow(
        String id,
        String goalId,
        String title,
        String measureUnit,
        BigDecimal targetValue,
        BigDecimal factValue,
        BigDecimal weight,
        LocalDate factUpdatedOn) {
}
