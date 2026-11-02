package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.summary.Summary;
import ru.kkd.service.IssueQuery;
import ru.kkd.service.IssueService;
import ru.kkd.service.KkdException;
import ru.kkd.service.RunQueryService;
import ru.kkd.service.SummaryQuery;
import ru.kkd.service.SummaryService;

/** Раздел 8.5 design.md и порядок проверок раздела 8.1. Тело сверяется целой строкой: так виден и единый формат. */
@WebMvcTest({KkdController.class, RunController.class})
class ApiErrorsWebTest {

    private static final String SUMMARY = "/api/kkd/summary";
    private static final String ISSUES = "/api/kkd/issues";
    private static final String RUNS = "/api/runs";

    private static final String RULE_CODE_MESSAGE = "Параметр rule_code должен быть одним из значений: "
            + "GOAL_NO_OWNER, GOAL_NO_KR, KR_WEIGHTS_SUM, CASCADE_NO_PARENT, KR_NO_TARGET, KR_STALE_PROGRESS";
    private static final String SEVERITY_MESSAGE = "Параметр severity должен быть одним из значений: critical, major, minor";
    private static final String LIMIT_MESSAGE = "Параметр limit должен быть целым числом от 1 до 200";
    private static final String OFFSET_MESSAGE = "Параметр offset должен быть целым числом от 0 до 2147483647";
    private static final String DB_NOT_READY_MESSAGE = "База данных не готова, повторите запрос позже";
    private static final String INTERNAL_MESSAGE = "Внутренняя ошибка сервера";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SummaryService summaryService;

    @MockitoBean
    private IssueService issueService;

    @MockitoBean
    private RunQueryService runQueryService;

    // 404: прогон и подразделение

    @Test
    void unknownRunInSummaryIs404() throws Exception {
        givenSummaryFails(KkdException.runNotFound("RUN-999"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-999").param("unit_id", "HQ-CIB")),
                404, "RUN_NOT_FOUND", "Прогон RUN-999 не найден");
    }

    @Test
    void unknownUnitInSummaryIs404() throws Exception {
        givenSummaryFails(KkdException.unitNotFound("NOPE"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-002").param("unit_id", "NOPE")),
                404, "UNIT_NOT_FOUND", "Подразделение NOPE не найдено");
    }

    @Test
    void unknownRunInIssuesIs404() throws Exception {
        givenIssuesFail(KkdException.runNotFound("abc"));

        assertError(perform(get(ISSUES).param("run_id", "abc")), 404, "RUN_NOT_FOUND", "Прогон abc не найден");
    }

    @Test
    void unknownUnitInIssuesIs404() throws Exception {
        givenIssuesFail(KkdException.unitNotFound("NOPE"));

        assertError(perform(get(ISSUES).param("run_id", "RUN-002").param("unit_id", "NOPE")),
                404, "UNIT_NOT_FOUND", "Подразделение NOPE не найдено");
    }

    // 409: прогон не завершён

    @Test
    void failedRunInSummaryIs409() throws Exception {
        givenSummaryFails(KkdException.runFailed("RUN-009"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-009").param("unit_id", "HQ-CIB")),
                409, "RUN_FAILED", "Прогон RUN-009 завершился с ошибкой");
    }

    @Test
    void runningRunInSummaryIs409() throws Exception {
        givenSummaryFails(KkdException.runInProgress("RUN-010"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-010").param("unit_id", "HQ-CIB")),
                409, "RUN_IN_PROGRESS", "Прогон RUN-010 ещё выполняется");
    }

    @Test
    void failedRunInIssuesIs409() throws Exception {
        givenIssuesFail(KkdException.runFailed("RUN-009"));

        assertError(perform(get(ISSUES).param("run_id", "RUN-009")),
                409, "RUN_FAILED", "Прогон RUN-009 завершился с ошибкой");
    }

    @Test
    void runningRunInIssuesIs409() throws Exception {
        givenIssuesFail(KkdException.runInProgress("RUN-010"));

        assertError(perform(get(ISSUES).param("run_id", "RUN-010")),
                409, "RUN_IN_PROGRESS", "Прогон RUN-010 ещё выполняется");
    }

    // 422: форма параметров. Сервисы не вызываются

    @Test
    void brokenQueryFoundByTomcatIs422() throws Exception {
        assertInvalidParam(
                perform(get(ISSUES).param("run_id", "RUN-002")
                        .requestAttr("org.apache.catalina.parameter_parse_failed", Boolean.TRUE)),
                "Строка запроса содержит испорченное кодирование");
    }

    /** MockMvc обходит Tomcat: видно только значение после него. Ответ самого Tomcat проверяет шаг 3 scripts/check-chain.sh. */
    @Test
    void bracketsInLimitAre422() throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("limit", "[1]")),
                "Параметр limit должен быть целым числом от 1 до 200");
    }

    @Test
    void summaryWithoutParametersIs422() throws Exception {
        assertInvalidParam(perform(get(SUMMARY)), "Параметр run_id обязателен");
    }

    @Test
    void summaryWithoutUnitIs422() throws Exception {
        assertInvalidParam(perform(get(SUMMARY).param("run_id", "RUN-002")), "Параметр unit_id обязателен");
    }

    @Test
    void summaryWithEmptyRunIs422() throws Exception {
        assertInvalidParam(perform(get(SUMMARY).param("run_id", "").param("unit_id", "HQ-CIB")),
                "Параметр run_id не должен быть пустым");
    }

    @Test
    void summaryWithEmptyUnitIs422() throws Exception {
        assertInvalidParam(perform(get(SUMMARY).param("run_id", "RUN-002").param("unit_id", "")),
                "Параметр unit_id не должен быть пустым");
    }

    @Test
    void nullCharInRunIs422() throws Exception {
        assertInvalidParam(perform(get(SUMMARY).param("run_id", "RUN\0").param("unit_id", "HQ-CIB")),
                "Параметр run_id содержит недопустимый символ");
    }

    @Test
    void nullCharInUnitOfIssuesIs422() throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("unit_id", "\0")),
                "Параметр unit_id содержит недопустимый символ");
    }

    @Test
    void issuesWithoutRunIs422() throws Exception {
        assertInvalidParam(perform(get(ISSUES)), "Параметр run_id обязателен");
    }

    @Test
    void issuesWithEmptyUnitIs422() throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("unit_id", "")),
                "Параметр unit_id не должен быть пустым");
    }

    @ParameterizedTest(name = "rule_code=[{0}]")
    @ValueSource(strings = {"", "NOPE", "goal_no_kr"})
    void wrongRuleCodeIs422(String ruleCode) throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("rule_code", ruleCode)),
                RULE_CODE_MESSAGE);
    }

    @ParameterizedTest(name = "severity=[{0}]")
    @ValueSource(strings = {"", "MINOR", "blocker"})
    void wrongSeverityIs422(String severity) throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("severity", severity)),
                SEVERITY_MESSAGE);
    }

    @ParameterizedTest(name = "limit=[{0}]")
    @ValueSource(strings = {"abc", "", "0", "-1", "201", "+5", " 5", "1.0", "٣"})
    void wrongLimitIs422(String limit) throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("limit", limit)), LIMIT_MESSAGE);
    }

    @ParameterizedTest(name = "offset=[{0}]")
    @ValueSource(strings = {"abc", "", "-1", "2147483648", "+5", " 5", "٣"})
    void wrongOffsetIs422(String offset) throws Exception {
        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-002").param("offset", offset)), OFFSET_MESSAGE);
    }

    // Порядок проверок раздела 8.1: форма раньше базы, первая найденная ошибка

    @Test
    void formIsCheckedBeforeRunExists() throws Exception {
        givenIssuesFail(KkdException.runNotFound("RUN-999"));

        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-999").param("limit", "-1")), LIMIT_MESSAGE);
    }

    @Test
    void formIsCheckedBeforeRunExistsInSummary() throws Exception {
        givenSummaryFails(KkdException.runNotFound("RUN-999"));

        assertInvalidParam(perform(get(SUMMARY).param("run_id", "RUN-999")), "Параметр unit_id обязателен");
    }

    @Test
    void formIsCheckedBeforeStateOfRun() throws Exception {
        givenIssuesFail(KkdException.runFailed("RUN-009"));

        assertInvalidParam(perform(get(ISSUES).param("run_id", "RUN-009").param("severity", "x")), SEVERITY_MESSAGE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''     | ''     | x          | x     | x | x | Параметр run_id не должен быть пустым",
            "RUN-1  | ''     | x          | x     | x | x | Параметр unit_id не должен быть пустым",
            "RUN-1  | U      | x          | x     | x | x | Параметр rule_code должен быть одним из значений: GOAL_NO_OWNER, GOAL_NO_KR, KR_WEIGHTS_SUM, CASCADE_NO_PARENT, KR_NO_TARGET, KR_STALE_PROGRESS",
            "RUN-1  | U      | GOAL_NO_KR | x     | x | x | Параметр severity должен быть одним из значений: critical, major, minor",
            "RUN-1  | U      | GOAL_NO_KR | minor | x | x | Параметр limit должен быть целым числом от 1 до 200",
            "RUN-1  | U      | GOAL_NO_KR | minor | 1 | x | Параметр offset должен быть целым числом от 0 до 2147483647"})
    void firstErrorFollowsOrderOfParameters(
            String runId, String unitId, String ruleCode, String severity, String limit, String offset, String message)
            throws Exception {
        assertInvalidParam(
                perform(get(ISSUES)
                        .param("run_id", runId)
                        .param("unit_id", unitId)
                        .param("rule_code", ruleCode)
                        .param("severity", severity)
                        .param("limit", limit)
                        .param("offset", offset)),
                message);
    }

    // 503 и 500 при ошибках базы

    @Test
    void missingTableInSummaryIs503() throws Exception {
        when(summaryService.getSummary(any())).thenThrow(badSql("42P01"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-002").param("unit_id", "HQ-CIB")),
                503, "DB_NOT_READY", DB_NOT_READY_MESSAGE);
    }

    @Test
    void missingTableInIssuesIs503() throws Exception {
        when(issueService.findIssues(any())).thenThrow(badSql("42P01"));

        assertError(perform(get(ISSUES).param("run_id", "RUN-002")), 503, "DB_NOT_READY", DB_NOT_READY_MESSAGE);
    }

    @Test
    void missingTableInRunsIs503() throws Exception {
        when(runQueryService.listRuns()).thenThrow(badSql("42P01"));

        assertError(perform(get(RUNS)), 503, "DB_NOT_READY", DB_NOT_READY_MESSAGE);
    }

    @Test
    void noConnectionIs503() throws Exception {
        when(runQueryService.listRuns()).thenThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));

        assertError(perform(get(RUNS)), 503, "DB_NOT_READY", DB_NOT_READY_MESSAGE);
    }

    @Test
    void otherSqlErrorInSummaryIs500() throws Exception {
        when(summaryService.getSummary(any())).thenThrow(badSql("42703"));

        assertError(perform(get(SUMMARY).param("run_id", "RUN-002").param("unit_id", "HQ-CIB")),
                500, "INTERNAL_ERROR", INTERNAL_MESSAGE);
    }

    @Test
    void otherSqlErrorInIssuesIs500() throws Exception {
        when(issueService.findIssues(any())).thenThrow(badSql("42703"));

        assertError(perform(get(ISSUES).param("run_id", "RUN-002")), 500, "INTERNAL_ERROR", INTERNAL_MESSAGE);
    }

    // 500 без стека

    @Test
    void unexpectedErrorHidesDetails() throws Exception {
        when(runQueryService.listRuns()).thenThrow(new IllegalStateException("секретная подробность"));

        MockHttpServletResponse response = perform(get(RUNS));

        assertError(response, 500, "INTERNAL_ERROR", INTERNAL_MESSAGE);
        assertThat(body(response)).doesNotContain("секретная", "IllegalStateException", "java.", "at ru.kkd");
    }

    @Test
    void sqlErrorHidesSqlText() throws Exception {
        when(runQueryService.listRuns()).thenThrow(badSql("42703"));

        assertThat(body(perform(get(RUNS)))).doesNotContain("SELECT", "goal_snapshot", "BadSqlGrammar");
    }

    @Test
    void kkdExceptionWithCodeOutsideTableIsInternalError() throws Exception {
        when(runQueryService.listRuns()).thenThrow(new KkdException("SOME_OTHER_CODE", "Сообщение"));

        // Для статуса 500 в таблице раздела 8.5 есть только INTERNAL_ERROR.
        assertError(perform(get(RUNS)), 500, "INTERNAL_ERROR", INTERNAL_MESSAGE);
    }

    // Ошибки Spring MVC

    @Test
    void unknownPathIs404() throws Exception {
        assertError(perform(get("/api/nope")), 404, "NOT_FOUND", "Путь не найден");
    }

    @Test
    void errorPathOfSpringBootIsUnknownPath() throws Exception {
        assertError(perform(get("/error")), 404, "NOT_FOUND", "Путь не найден");
    }

    @Test
    void postToRunsIs405AndKeepsAllow() throws Exception {
        MockHttpServletResponse response = perform(post(RUNS));

        assertError(response, 405, "METHOD_NOT_ALLOWED", "Метод не поддерживается");
        assertThat(response.getHeader(HttpHeaders.ALLOW)).contains("GET");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/kkd/summary", "PUT, /api/kkd/summary", "PATCH, /api/kkd/summary", "DELETE, /api/kkd/summary",
            "POST, /api/kkd/issues", "PUT, /api/kkd/issues", "PATCH, /api/kkd/issues", "DELETE, /api/kkd/issues",
            "POST, /api/runs", "PUT, /api/runs", "PATCH, /api/runs", "DELETE, /api/runs"})
    void onlyGetIsAllowedOnEachPath(String method, String path) throws Exception {
        assertError(perform(request(HttpMethod.valueOf(method), path)),
                405, "METHOD_NOT_ALLOWED", "Метод не поддерживается");
    }

    @Test
    void brokenFormBodyOnPutIs405() throws Exception {
        assertError(perform(put(RUNS).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=%zz")),
                405, "METHOD_NOT_ALLOWED", "Метод не поддерживается");
    }

    @Test
    void notAcceptableIs406InUnifiedFormat() throws Exception {
        when(summaryService.getSummary(any())).thenReturn(summary());

        assertError(
                perform(get(SUMMARY).param("run_id", "RUN-002").param("unit_id", "HQ-CIB").accept(MediaType.APPLICATION_XML)),
                406, "REQUEST_NOT_SUPPORTED", "Запрос не поддерживается");
    }

    @Test
    void notAcceptableInRunsIs406InUnifiedFormat() throws Exception {
        when(runQueryService.listRuns()).thenReturn(List.of());

        assertError(perform(get(RUNS).accept(MediaType.TEXT_PLAIN)),
                406, "REQUEST_NOT_SUPPORTED", "Запрос не поддерживается");
    }

    @Test
    void otherClientStatusOfSpringKeepsStatusAndHeaders() throws Exception {
        ResponseEntity<Object> response = new ApiExceptionHandler().handleException(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of("REQUEST_NOT_SUPPORTED", "Запрос не поддерживается"));
    }

    @Test
    void badRequestOfSpringKeepsStatus() throws Exception {
        ResponseEntity<Object> response = new ApiExceptionHandler().handleException(
                new MissingServletRequestParameterException("x", "String"), webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of("REQUEST_NOT_SUPPORTED", "Запрос не поддерживается"));
    }

    @Test
    void serverStatusOfSpringBecomes500() throws Exception {
        ResponseEntity<Object> response = new ApiExceptionHandler().handleException(
                new AsyncRequestTimeoutException(), webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_MESSAGE));
    }

    // Единый формат и JSON при любом Accept

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/json", "application/xml", "text/html", "text/plain", "*/*"})
    void errorIsJsonForAnyAccept(String accept) throws Exception {
        assertInvalidParam(perform(get(SUMMARY).header(HttpHeaders.ACCEPT, accept)), "Параметр run_id обязателен");
        assertError(perform(get("/api/nope").header(HttpHeaders.ACCEPT, accept)), 404, "NOT_FOUND", "Путь не найден");
        assertError(perform(post(RUNS).header(HttpHeaders.ACCEPT, accept)),
                405, "METHOD_NOT_ALLOWED", "Метод не поддерживается");

        when(runQueryService.listRuns()).thenThrow(badSql("42P01"));
        assertError(perform(get(RUNS).header(HttpHeaders.ACCEPT, accept)), 503, "DB_NOT_READY", DB_NOT_READY_MESSAGE);
    }

    @Test
    void codeConstantsEqualTheirNames() {
        assertThat(ApiExceptionHandler.NOT_FOUND).isEqualTo("NOT_FOUND");
        assertThat(ApiExceptionHandler.METHOD_NOT_ALLOWED).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(ApiExceptionHandler.REQUEST_NOT_SUPPORTED).isEqualTo("REQUEST_NOT_SUPPORTED");
        assertThat(ApiExceptionHandler.DB_NOT_READY).isEqualTo("DB_NOT_READY");
        assertThat(ApiExceptionHandler.INTERNAL_ERROR).isEqualTo("INTERNAL_ERROR");
    }

    private void givenSummaryFails(KkdException error) {
        when(summaryService.getSummary(any(SummaryQuery.class))).thenThrow(error);
    }

    private void givenIssuesFail(KkdException error) {
        when(issueService.findIssues(any(IssueQuery.class))).thenThrow(error);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertInvalidParam(MockHttpServletResponse response, String message) throws Exception {
        assertError(response, 422, "INVALID_PARAM", message);
        verifyNoInteractions(summaryService, issueService);
    }

    private static void assertError(MockHttpServletResponse response, int status, String code, String message)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body(response)).isEqualTo("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private static BadSqlGrammarException badSql(String sqlState) {
        return new BadSqlGrammarException(
                "выборка", "SELECT * FROM goal_snapshot", new SQLException("ошибка базы", sqlState));
    }

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest());
    }

    private static Summary summary() {
        Run run = new Run("RUN-002", LocalDate.of(2026, 10, 26), RunStatus.COMPLETED, null, null);
        return new Summary(run, null, new Unit("HQ-CIB", "Департамент", null), List.of(), List.of(),
                new DeltaCounts(null, null, null));
    }
}
