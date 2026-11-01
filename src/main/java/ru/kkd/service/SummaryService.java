package ru.kkd.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.org.UnitTree;
import ru.kkd.domain.summary.RunData;
import ru.kkd.domain.summary.Summary;
import ru.kkd.domain.summary.SummaryAssembler;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;
import ru.kkd.repository.UnitRepository;

@Service
public class SummaryService {

    private final RunRepository runs;
    private final UnitRepository units;
    private final SnapshotRepository snapshots;
    private final IssueRepository issues;
    private final SummaryAssembler assembler;

    public SummaryService(
            RunRepository runs,
            UnitRepository units,
            SnapshotRepository snapshots,
            IssueRepository issues,
            SummaryAssembler assembler) {
        this.runs = runs;
        this.units = units;
        this.snapshots = snapshots;
        this.issues = issues;
        this.assembler = assembler;
    }

    /** Порядок проверок: прогон, подразделение, состояние прогона. Раздел 8.1 design.md. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary getSummary(SummaryQuery query) {
        Run run = runs.findById(query.runId()).orElseThrow(() -> KkdException.runNotFound(query.runId()));
        Unit unit = units.findById(query.unitId()).orElseThrow(() -> KkdException.unitNotFound(query.unitId()));
        RunQueryService.requireCompleted(run);

        return assembler.assemble(load(run), loadBase(run), unit, new UnitTree(units.findAll()));
    }

    // База записана при завершении прогона и заново не выбирается: раздел 6.4. Строка есть всегда, это FK.
    private RunData loadBase(Run run) {
        if (run.comparedToRunId() == null) {
            return null;
        }
        return load(runs.findById(run.comparedToRunId()).orElseThrow());
    }

    private RunData load(Run run) {
        return new RunData(run, snapshots.load(run.id(), run.snapshotDate()), issues.findByRun(run.id()));
    }
}
