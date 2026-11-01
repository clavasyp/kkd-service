package ru.kkd.service;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ru.kkd.domain.delta.BaseRunSelector;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Snapshot;
import ru.kkd.domain.rules.RuleCatalog;
import ru.kkd.domain.rules.RuleEngine;
import ru.kkd.repository.IssueRepository;
import ru.kkd.repository.RuleRepository;
import ru.kkd.repository.RunRepository;
import ru.kkd.repository.SnapshotRepository;

/** Выполняет один прогон: разделы 6.2 и 6.3 design.md. */
@Service
public class RunProcessor {

    private static final String SNAPSHOT_TOO_OLD_ERROR =
            "Снимка нет, а дата снимка раньше последнего завершённого прогона";

    // Текст постоянный: GET /api/runs отдаёт error как есть, а текст исключения показал бы SQL и имена таблиц.
    public static final String GENERAL_ERROR =
            "Внутренняя ошибка при выполнении прогона, подробности в журнале сервиса";

    private static final Logger log = LoggerFactory.getLogger(RunProcessor.class);

    private final RunRepository runs;
    private final SnapshotRepository snapshots;
    private final RuleRepository rules;
    private final IssueRepository issues;
    private final RuleEngine ruleEngine;
    private final BaseRunSelector baseRunSelector;
    private final TransactionOperations transactions;

    public RunProcessor(
            RunRepository runs,
            SnapshotRepository snapshots,
            RuleRepository rules,
            IssueRepository issues,
            RuleEngine ruleEngine,
            BaseRunSelector baseRunSelector,
            TransactionOperations transactions) {
        this.runs = runs;
        this.snapshots = snapshots;
        this.rules = rules;
        this.issues = issues;
        this.ruleEngine = ruleEngine;
        this.baseRunSelector = baseRunSelector;
        this.transactions = transactions;
    }

    /**
     * Если база не готова или записать failed не удалось, исключение идёт выше:
     * прогон остаётся running и берётся снова в следующем такте.
     */
    public void process(Run run) {
        calculateInTransaction(run).ifPresent(error ->
                transactions.executeWithoutResult(status -> runs.markFailed(run.id(), error)));
    }

    /** Транзакция прогона. Возвращает текст для error, если прогон не удался: он пишется уже после неё. */
    private Optional<String> calculateInTransaction(Run run) {
        try {
            return transactions.execute(status -> calculate(run));
        } catch (RuntimeException e) {
            if (DbState.isNotReady(e)) {
                throw e;
            }
            log.error("Прогон {} завершился сбоем", run.id(), e);
            return Optional.of(GENERAL_ERROR);
        }
    }

    private Optional<String> calculate(Run run) {
        List<Run> allRuns = runs.findAll();

        if (!snapshots.exists(run.id())) {
            if (isBeforeLastCompleted(run, allRuns)) {
                log.warn("Прогон {}: снимка нет, а дата {} раньше последнего завершённого прогона",
                        run.id(), run.snapshotDate());
                return Optional.of(SNAPSHOT_TOO_OLD_ERROR);
            }
            snapshots.freeze(run.id());
        }

        Snapshot snapshot = snapshots.load(run.id(), run.snapshotDate());
        RuleCatalog catalog = rules.loadCatalog();
        List<Issue> found = ruleEngine.check(snapshot, catalog);
        String comparedToRunId = baseRunSelector.select(run, allRuns).map(Run::id).orElse(null);

        issues.saveAll(run.id(), found);
        runs.markCompleted(run.id(), comparedToRunId);
        log.info("Прогон {}: проблем {}, {}", run.id(), found.size(),
                comparedToRunId == null ? "сравнения нет" : "сравнение с " + comparedToRunId);
        return Optional.empty();
    }

    private boolean isBeforeLastCompleted(Run run, List<Run> allRuns) {
        return allRuns.stream()
                .filter(other -> other.status() == RunStatus.COMPLETED)
                .anyMatch(completed -> run.snapshotDate().isBefore(completed.snapshotDate()));
    }
}
