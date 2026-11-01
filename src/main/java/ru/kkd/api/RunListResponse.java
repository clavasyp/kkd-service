package ru.kkd.api;

import java.time.LocalDate;
import java.util.List;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunListItem;
import ru.kkd.domain.model.RunStatus;

/** Ответ GET /api/runs: раздел 8.2 design.md. Порядок полей в JSON задан порядком компонентов записи. */
public record RunListResponse(List<Item> items, int total) {

    public static RunListResponse from(List<RunListItem> runs) {
        return new RunListResponse(runs.stream().map(Item::from).toList(), runs.size());
    }

    public record Item(
            String id,
            String status,
            LocalDate snapshotDate,
            String comparedToRunId,
            Totals totals,
            String error) {

        // База сравнения и итоги есть только у completed, причина только у failed.
        public static Item from(RunListItem item) {
            Run run = item.run();
            boolean completed = run.status() == RunStatus.COMPLETED;
            return new Item(
                    run.id(),
                    EnumText.format(run.status()),
                    run.snapshotDate(),
                    completed ? run.comparedToRunId() : null,
                    completed ? new Totals(item.issuesTotal()) : null,
                    run.status() == RunStatus.FAILED ? run.error() : null);
        }
    }

    public record Totals(int issuesTotal) {
    }
}
