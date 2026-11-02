package ru.kkd.domain.delta;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;

public class BaseRunSelector {

    // id сравниваются по байтам UTF-8, как COLLATE "C" в базе, а значит и такт, и /api/runs.
    // String.compareTo сравнивает единицы UTF-16 и расходится с базой на символах вне BMP.
    private static final Comparator<Run> RUN_ORDER = Comparator
            .comparing(Run::snapshotDate)
            .thenComparing(run -> run.id().getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned);

    /** Последний completed раньше текущего по (snapshot_date, id). Упавшие и выполняющиеся пропускаются. */
    public Optional<Run> select(Run current, List<Run> runs) {
        return runs.stream()
                .filter(run -> run.status() == RunStatus.COMPLETED)
                .filter(run -> RUN_ORDER.compare(run, current) < 0)
                .max(RUN_ORDER);
    }
}
