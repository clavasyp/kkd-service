package ru.kkd.domain.metrics;

import java.math.BigDecimal;

/**
 * Значения метрик ветки в одном прогоне. Доли с одним знаком после точки, null, если считать не из чего.
 * Счётчик без дробной части. Если базы сравнения нет, SummaryAssembler подставляет вместо прошлых значений
 * запись из трёх null.
 */
public record MetricValues(BigDecimal cleanGoalsShare, BigDecimal issuesTotal, BigDecimal cascadeCoverage) {
}
