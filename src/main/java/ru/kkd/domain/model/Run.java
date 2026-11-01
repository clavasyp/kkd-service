package ru.kkd.domain.model;

import java.time.LocalDate;

/** Прогон. Могут быть null: comparedToRunId, error. */
public record Run(
        String id,
        LocalDate snapshotDate,
        RunStatus status,
        String comparedToRunId,
        String error) {
}
