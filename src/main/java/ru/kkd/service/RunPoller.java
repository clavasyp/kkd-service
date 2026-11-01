package ru.kkd.service;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.kkd.domain.model.Run;
import ru.kkd.repository.RunRepository;

/** Такт обработчика: раздел 6.2 design.md. Работает в одном потоке с паузой после конца такта. */
@Component
public class RunPoller {

    private static final Logger log = LoggerFactory.getLogger(RunPoller.class);

    private final RunRepository runs;
    private final RunProcessor processor;

    // Неготовая база пишется в журнал при смене состояния, а не на каждом такте.
    private boolean dbReady = true;

    public RunPoller(RunRepository runs, RunProcessor processor) {
        this.runs = runs;
        this.processor = processor;
    }

    /** Исключение завершает такт: прогон остаётся running и берётся снова в следующем такте. */
    @Scheduled(fixedDelayString = "${kkd.run-poll-ms}")
    public void tick() {
        try {
            processRunningRuns();
            noteDbReady();
        } catch (RuntimeException e) {
            if (DbState.isNotReady(e)) {
                noteDbNotReady(e);
            } else {
                noteDbReady();
                log.error("Такт прерван ошибкой", e);
            }
        }
    }

    private void processRunningRuns() {
        Optional<Run> next = runs.findNextRunning();
        while (next.isPresent()) {
            processor.process(next.get());
            next = runs.findNextRunning();
        }
    }

    private void noteDbReady() {
        if (!dbReady) {
            log.info("База снова готова");
            dbReady = true;
        }
    }

    private void noteDbNotReady(RuntimeException e) {
        if (dbReady) {
            log.warn("База не готова, такты пропускаются: {}", DbState.shortReason(e));
            dbReady = false;
        }
    }
}
