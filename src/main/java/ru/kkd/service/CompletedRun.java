package ru.kkd.service;

import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;

/** Неуспешный и выполняющийся прогон результатов не дают: общая проверка сводки и списка, раздел 8.5 design.md. */
final class CompletedRun {

    private CompletedRun() {
    }

    static void require(Run run) {
        if (run.status() == RunStatus.FAILED) {
            throw KkdException.runFailed(run.id());
        }
        if (run.status() == RunStatus.RUNNING) {
            throw KkdException.runInProgress(run.id());
        }
    }
}
