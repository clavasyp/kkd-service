package ru.kkd.domain.delta;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;

public class BaseRunSelector {

    private static final Comparator<Run> RUN_ORDER = Comparator
            .comparing(Run::snapshotDate)
            .thenComparing(Run::id);

    /** Последний completed раньше текущего по (snapshot_date, id). Упавшие и выполняющиеся пропускаются. */
    public Optional<Run> select(Run current, List<Run> runs) {
        return runs.stream()
                .filter(run -> run.status() == RunStatus.COMPLETED)
                .filter(run -> RUN_ORDER.compare(run, current) < 0)
                .max(RUN_ORDER);
    }
}
