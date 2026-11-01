package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Контракт JSON по разделу 8 design.md на настоящем ObjectMapper Spring Boot: имена snake_case заданы глобально.
 * Сверка целой строкой проверяет сразу имена полей, null, формат даты и порядок полей.
 * Новый DTO: своя группа тестов ниже, значение идёт через json(...).
 */
@JsonTest
class JsonContractTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

    @Autowired
    private ObjectMapper objectMapper;

    // GET /api/runs, раздел 8.2

    @Test
    void completedRunRowHasFieldsAndOrderOfSection82() throws Exception {
        RunListResponse.Item row = new RunListResponse.Item(
                "RUN-008", "completed", DATE, "RUN-007", new RunListResponse.Totals(43), null);

        assertThat(json(row)).isEqualTo("""
                {"id":"RUN-008","status":"completed","snapshot_date":"2026-10-26",\
                "compared_to_run_id":"RUN-007","totals":{"issues_total":43},"error":null}""");
    }

    @Test
    void firstCompletedRunKeepsNullComparedToRunId() throws Exception {
        RunListResponse.Item row = new RunListResponse.Item(
                "RUN-007", "completed", DATE, null, new RunListResponse.Totals(35), null);

        assertThat(json(row)).isEqualTo("""
                {"id":"RUN-007","status":"completed","snapshot_date":"2026-10-26",\
                "compared_to_run_id":null,"totals":{"issues_total":35},"error":null}""");
    }

    @Test
    void failedRunRowKeepsNullFieldsAndRussianError() throws Exception {
        RunListResponse.Item row = new RunListResponse.Item(
                "RUN-009", "failed", DATE, null, null,
                "Снимка нет, а дата снимка раньше последнего завершённого прогона");

        assertThat(json(row)).isEqualTo("""
                {"id":"RUN-009","status":"failed","snapshot_date":"2026-10-26",\
                "compared_to_run_id":null,"totals":null,\
                "error":"Снимка нет, а дата снимка раньше последнего завершённого прогона"}""");
    }

    @Test
    void runningRunRowKeepsAllNullFields() throws Exception {
        RunListResponse.Item row = new RunListResponse.Item("RUN-010", "running", DATE, null, null, null);

        assertThat(json(row)).isEqualTo("""
                {"id":"RUN-010","status":"running","snapshot_date":"2026-10-26",\
                "compared_to_run_id":null,"totals":null,"error":null}""");
    }

    @Test
    void zeroIssuesAreWrittenAsNumber() throws Exception {
        assertThat(json(new RunListResponse.Totals(0))).isEqualTo("""
                {"issues_total":0}""");
    }

    @Test
    void responseHasItemsThenTotal() throws Exception {
        RunListResponse response = new RunListResponse(
                List.of(new RunListResponse.Item("RUN-010", "running", DATE, null, null, null)), 1);

        assertThat(json(response)).isEqualTo("""
                {"items":[{"id":"RUN-010","status":"running","snapshot_date":"2026-10-26",\
                "compared_to_run_id":null,"totals":null,"error":null}],"total":1}""");
    }

    @Test
    void emptyResponseKeepsEmptyItemsAndZeroTotal() throws Exception {
        assertThat(json(new RunListResponse(List.of(), 0))).isEqualTo("""
                {"items":[],"total":0}""");
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
