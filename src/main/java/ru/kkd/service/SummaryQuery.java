package ru.kkd.service;

/** Разобранные параметры GET /api/kkd/summary. Оба обязательны. */
public record SummaryQuery(String runId, String unitId) {
}
