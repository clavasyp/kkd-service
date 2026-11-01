package ru.kkd.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KkdExceptionTest {

    @Test
    void codesEqualTheirNames() {
        assertThat(KkdException.INVALID_PARAM).isEqualTo("INVALID_PARAM");
        assertThat(KkdException.RUN_NOT_FOUND).isEqualTo("RUN_NOT_FOUND");
        assertThat(KkdException.UNIT_NOT_FOUND).isEqualTo("UNIT_NOT_FOUND");
        assertThat(KkdException.RUN_FAILED).isEqualTo("RUN_FAILED");
        assertThat(KkdException.RUN_IN_PROGRESS).isEqualTo("RUN_IN_PROGRESS");
    }

    @Test
    void factoriesGiveCodeAndRussianMessage() {
        assertCodeAndMessage(KkdException.invalidParam("Текст"), "INVALID_PARAM", "Текст");
        assertCodeAndMessage(KkdException.runNotFound("RUN-999"), "RUN_NOT_FOUND", "Прогон RUN-999 не найден");
        assertCodeAndMessage(KkdException.unitNotFound("NOPE"), "UNIT_NOT_FOUND", "Подразделение NOPE не найдено");
        assertCodeAndMessage(KkdException.runFailed("RUN-009"), "RUN_FAILED", "Прогон RUN-009 завершился с ошибкой");
        assertCodeAndMessage(KkdException.runInProgress("RUN-010"), "RUN_IN_PROGRESS", "Прогон RUN-010 ещё выполняется");
    }

    private static void assertCodeAndMessage(KkdException error, String code, String message) {
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.getMessage()).isEqualTo(message);
    }
}
