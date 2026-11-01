package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunListItem;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.service.RunQueryService;

@WebMvcTest(RunController.class)
class RunControllerWebTest {

    private static final String SNAPSHOT_TOO_OLD = "Снимка нет, а дата снимка раньше последнего завершённого прогона";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RunQueryService runQueryService;

    // Форма раздела 8.2

    @Test
    void completedRunsHaveFormOfSection82() throws Exception {
        givenRuns(
                new RunListItem(run("RUN-008", "2026-10-26", RunStatus.COMPLETED, "RUN-007", null), 43),
                new RunListItem(run("RUN-007", "2026-10-19", RunStatus.COMPLETED, null, null), 35));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-008","status":"completed","snapshot_date":"2026-10-26",\
                "compared_to_run_id":"RUN-007","totals":{"issues_total":43},"error":null},\
                {"id":"RUN-007","status":"completed","snapshot_date":"2026-10-19",\
                "compared_to_run_id":null,"totals":{"issues_total":35},"error":null}\
                ],"total":2}""");
    }

    @Test
    void failedRunHasFormOfSection82() throws Exception {
        givenRuns(new RunListItem(run("RUN-009", "2026-10-20", RunStatus.FAILED, null, SNAPSHOT_TOO_OLD), 0));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-009","status":"failed","snapshot_date":"2026-10-20",\
                "compared_to_run_id":null,"totals":null,\
                "error":"Снимка нет, а дата снимка раньше последнего завершённого прогона"}\
                ],"total":1}""");
    }

    @Test
    void runningRunHasFormOfSection82() throws Exception {
        givenRuns(new RunListItem(run("RUN-010", "2026-10-27", RunStatus.RUNNING, null, null), 0));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-010","status":"running","snapshot_date":"2026-10-27",\
                "compared_to_run_id":null,"totals":null,"error":null}\
                ],"total":1}""");
    }

    @Test
    void runsOfAllStatusesKeepOrderOfService() throws Exception {
        givenRuns(
                new RunListItem(run("RUN-010", "2026-10-27", RunStatus.RUNNING, null, null), 0),
                new RunListItem(run("RUN-008", "2026-10-26", RunStatus.COMPLETED, "RUN-007", null), 43),
                new RunListItem(run("RUN-009", "2026-10-20", RunStatus.FAILED, null, SNAPSHOT_TOO_OLD), 0),
                new RunListItem(run("RUN-007", "2026-10-19", RunStatus.COMPLETED, null, null), 35));

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].id").value("RUN-010"))
                .andExpect(jsonPath("$.items[1].id").value("RUN-008"))
                .andExpect(jsonPath("$.items[2].id").value("RUN-009"))
                .andExpect(jsonPath("$.items[3].id").value("RUN-007"));
    }

    @Test
    void withoutRunsItemsAreEmptyAndTotalIsZero() throws Exception {
        givenRuns();

        assertThat(getRuns()).isEqualTo("""
                {"items":[],"total":0}""");
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, completed", "FAILED, failed", "RUNNING, running"})
    void statusIsLowerCase(RunStatus status, String expected) throws Exception {
        givenRuns(new RunListItem(run("RUN-008", "2026-10-26", status, null, null), 0));

        mvc.perform(get("/api/runs")).andExpect(jsonPath("$.items[0].status").value(expected));
    }

    // Поля зависят только от статуса, а не от того, что лежит в строке прогона

    @Test
    void failedRunHidesComparedToRunIdAndTotalsEvenIfTheyAreInData() throws Exception {
        givenRuns(new RunListItem(run("RUN-009", "2026-10-20", RunStatus.FAILED, "RUN-007", SNAPSHOT_TOO_OLD), 5));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-009","status":"failed","snapshot_date":"2026-10-20",\
                "compared_to_run_id":null,"totals":null,\
                "error":"Снимка нет, а дата снимка раньше последнего завершённого прогона"}\
                ],"total":1}""");
    }

    @Test
    void runningRunHidesAllResultFieldsEvenIfTheyAreInData() throws Exception {
        givenRuns(new RunListItem(run("RUN-010", "2026-10-27", RunStatus.RUNNING, "RUN-007", "старый текст"), 7));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-010","status":"running","snapshot_date":"2026-10-27",\
                "compared_to_run_id":null,"totals":null,"error":null}\
                ],"total":1}""");
    }

    @Test
    void completedRunHidesErrorEvenIfItIsInData() throws Exception {
        givenRuns(new RunListItem(run("RUN-008", "2026-10-26", RunStatus.COMPLETED, "RUN-007", "старый текст"), 43));

        assertThat(getRuns()).isEqualTo("""
                {"items":[\
                {"id":"RUN-008","status":"completed","snapshot_date":"2026-10-26",\
                "compared_to_run_id":"RUN-007","totals":{"issues_total":43},"error":null}\
                ],"total":1}""");
    }

    @Test
    void completedRunWithoutIssuesHasZeroAndNotNullTotals() throws Exception {
        givenRuns(new RunListItem(run("RUN-008", "2026-10-26", RunStatus.COMPLETED, null, null), 0));

        mvc.perform(get("/api/runs")).andExpect(jsonPath("$.items[0].totals.issues_total").value(0));
    }

    // Общие правила раздела 8

    @Test
    void unknownQueryParametersAreIgnored() throws Exception {
        givenRuns(new RunListItem(run("RUN-010", "2026-10-27", RunStatus.RUNNING, null, null), 0));

        String withParameters = bodyOf(get("/api/runs").param("limit", "abc").param("foo", "bar"));

        assertThat(withParameters).isEqualTo(getRuns());
    }

    @Test
    void controllerTakesRunsFromService() throws Exception {
        givenRuns();

        getRuns();

        verify(runQueryService).listRuns();
    }

    private void givenRuns(RunListItem... items) {
        when(runQueryService.listRuns()).thenReturn(List.of(items));
    }

    private String getRuns() throws Exception {
        return bodyOf(get("/api/runs"));
    }

    // Тело читается как UTF-8: иначе MockMvc декодирует его как ISO-8859-1, и русский текст исказит сам тест.
    private String bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static Run run(String id, String snapshotDate, RunStatus status, String comparedToRunId, String error) {
        return new Run(id, LocalDate.parse(snapshotDate), status, comparedToRunId, error);
    }
}
