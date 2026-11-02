package ru.kkd.service;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.kkd.domain.model.RunListItem;
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
}
