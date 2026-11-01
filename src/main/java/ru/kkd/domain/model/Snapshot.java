package ru.kkd.domain.model;

import java.time.LocalDate;
import java.util.List;

/** Замороженные данные прогона: все цели, включая закрытые, и все КР. */
public record Snapshot(LocalDate snapshotDate, List<GoalRow> goals, List<KeyResultRow> keyResults) {

    // Копии только для чтения: правила работают со снимком по очереди, и одно не должно испортить данные другому.
    public Snapshot {
        goals = List.copyOf(goals);
        keyResults = List.copyOf(keyResults);
    }

    public List<KeyResultRow> keyResultsOf(String goalId) {
        return keyResults.stream()
                .filter(keyResult -> keyResult.goalId().equals(goalId))
                .toList();
    }
}
