package ru.kkd.service;

/** Ошибка, о которой сообщают клиенту. Несёт только code и message: статус HTTP выбирает ApiExceptionHandler. */
public class KkdException extends RuntimeException {

    public static final String INVALID_PARAM = "INVALID_PARAM";
    public static final String RUN_NOT_FOUND = "RUN_NOT_FOUND";
    public static final String UNIT_NOT_FOUND = "UNIT_NOT_FOUND";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String RUN_IN_PROGRESS = "RUN_IN_PROGRESS";

    private final String code;

    public KkdException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static KkdException invalidParam(String message) {
        return new KkdException(INVALID_PARAM, message);
    }

    public static KkdException runNotFound(String runId) {
        return new KkdException(RUN_NOT_FOUND, "Прогон " + runId + " не найден");
    }

    public static KkdException unitNotFound(String unitId) {
        return new KkdException(UNIT_NOT_FOUND, "Подразделение " + unitId + " не найдено");
    }

    public static KkdException runFailed(String runId) {
        return new KkdException(RUN_FAILED, "Прогон " + runId + " завершился с ошибкой");
    }

    public static KkdException runInProgress(String runId) {
        return new KkdException(RUN_IN_PROGRESS, "Прогон " + runId + " ещё выполняется");
    }
}
