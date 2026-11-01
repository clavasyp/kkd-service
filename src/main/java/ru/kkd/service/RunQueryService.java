package ru.kkd.service;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunListItem;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.repository.RunRepository;

@Service
public class RunQueryService {

    private final RunRepository runs;

    public RunQueryService(RunRepository runs) {
        this.runs = runs;
    }

    /** Только читает базу: прогоны считает RunPoller. Новые сверху. */
    public List<RunListItem> listRuns() {
        return runs.findAllWithIssueCounts();
    }

    /** Неуспешный и выполняющийся прогон результатов не дают: раздел 8.5 design.md. */
    static void requireCompleted(Run run) {
        if (run.status() == RunStatus.FAILED) {
            throw KkdException.runFailed(run.id());
        }
        if (run.status() == RunStatus.RUNNING) {
            throw KkdException.runInProgress(run.id());
        }
    }
}
