package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.metrics.MetricComparison;
import ru.kkd.domain.metrics.MetricKind;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.RunStatus;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.summary.SeverityCount;
import ru.kkd.domain.summary.Summary;
import ru.kkd.service.IssuePage;

/**
 * Контракт JSON по разделу 8 design.md на настоящем ObjectMapper Spring Boot: имена snake_case заданы глобально.
 * Сверка целой строкой проверяет сразу имена полей, null, формат даты и порядок полей.
 * Новый DTO: своя группа тестов ниже, значение идёт через json(...).
 */
@JsonTest
class JsonContractTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private static final Run RUN_008 = new Run("RUN-008", DATE, RunStatus.COMPLETED, "RUN-007", null);
    private static final Run RUN_007 = new Run("RUN-007", LocalDate.of(2026, 10, 19), RunStatus.COMPLETED, null, null);
    private static final Unit HQ = new Unit("HQ-CIB", "Департамент корпоративного бизнеса", null);

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

    // GET /api/kkd/summary, раздел 8.3. Числа условные, из примера раздела.

    @Test
    void summaryMatchesExampleOfSection83() throws Exception {
        Summary summary = new Summary(
                RUN_008,
                RUN_007,
                HQ,
                List.of(
                        MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, decimal("56.5"), decimal("62.7")),
                        MetricComparison.of(MetricKind.ISSUES_TOTAL, decimal("43"), decimal("35")),
                        MetricComparison.of(MetricKind.CASCADE_COVERAGE, decimal("80.0"), decimal("75.0"))),
                List.of(
                        new SeverityCount(Severity.CRITICAL, 2, 2, 0),
                        new SeverityCount(Severity.MAJOR, 23, 15, 8),
                        new SeverityCount(Severity.MINOR, 18, 18, 0)),
                new DeltaCounts(10, 33, 2));

        assertThat(json(SummaryResponse.from(summary))).isEqualTo("""
                {"run":{"id":"RUN-008","snapshot_date":"2026-10-26"},\
                "compared_to":{"id":"RUN-007","snapshot_date":"2026-10-19"},\
                "unit":{"id":"HQ-CIB","name":"Департамент корпоративного бизнеса"},\
                "metrics":[\
                {"key":"clean_goals_share","title":"Доля корректных целей","unit":"%",\
                "value":56.5,"previous":62.7,"delta":-6.2,"trend":"worse","higher_is_better":true},\
                {"key":"issues_total","title":"Найдено проблем","unit":"шт.",\
                "value":43,"previous":35,"delta":8,"trend":"worse","higher_is_better":false},\
                {"key":"cascade_coverage","title":"Покрытие каскадом","unit":"%",\
                "value":80.0,"previous":75.0,"delta":5.0,"trend":"better","higher_is_better":true}\
                ],\
                "issues_by_severity":[\
                {"code":"critical","current":2,"previous":2,"delta":0},\
                {"code":"major","current":23,"previous":15,"delta":8},\
                {"code":"minor","current":18,"previous":18,"delta":0}\
                ],\
                "delta":{"new":10,"persisting":33,"resolved":2}}""");
    }

    @Test
    void firstRunMatchesExampleOfSection83() throws Exception {
        Summary summary = new Summary(
                RUN_007,
                null,
                HQ,
                List.of(
                        MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, decimal("62.7"), null),
                        MetricComparison.of(MetricKind.ISSUES_TOTAL, decimal("35"), null),
                        MetricComparison.of(MetricKind.CASCADE_COVERAGE, decimal("75.0"), null)),
                List.of(
                        new SeverityCount(Severity.CRITICAL, 2, null, null),
                        new SeverityCount(Severity.MAJOR, 15, null, null),
                        new SeverityCount(Severity.MINOR, 18, null, null)),
                new DeltaCounts(null, null, null));

        assertThat(json(SummaryResponse.from(summary))).isEqualTo("""
                {"run":{"id":"RUN-007","snapshot_date":"2026-10-19"},\
                "compared_to":null,\
                "unit":{"id":"HQ-CIB","name":"Департамент корпоративного бизнеса"},\
                "metrics":[\
                {"key":"clean_goals_share","title":"Доля корректных целей","unit":"%",\
                "value":62.7,"previous":null,"delta":null,"trend":null,"higher_is_better":true},\
                {"key":"issues_total","title":"Найдено проблем","unit":"шт.",\
                "value":35,"previous":null,"delta":null,"trend":null,"higher_is_better":false},\
                {"key":"cascade_coverage","title":"Покрытие каскадом","unit":"%",\
                "value":75.0,"previous":null,"delta":null,"trend":null,"higher_is_better":true}\
                ],\
                "issues_by_severity":[\
                {"code":"critical","current":2,"previous":null,"delta":null},\
                {"code":"major","current":15,"previous":null,"delta":null},\
                {"code":"minor","current":18,"previous":null,"delta":null}\
                ],\
                "delta":{"new":null,"persisting":null,"resolved":null}}""");
    }

    @Test
    void metricValuesHaveNoNoise() throws Exception {
        assertThat(metricJson(MetricKind.CLEAN_GOALS_SHARE, "56.5", "62.7"))
                .contains("\"value\":56.5,\"previous\":62.7,\"delta\":-6.2,");
        assertThat(metricJson(MetricKind.CASCADE_COVERAGE, "80.0", "75.0"))
                .contains("\"value\":80.0,\"previous\":75.0,\"delta\":5.0,");
    }

    @Test
    void shareKeepsOneDecimalPlaceAtTopValue() throws Exception {
        // Форма 1E+2 была бы ошибкой контракта: раздел 5 обещает один знак после точки.
        assertThat(metricJson(MetricKind.CASCADE_COVERAGE, "100.0", "0.0"))
                .contains("\"value\":100.0,\"previous\":0.0,\"delta\":100.0,");
    }

    @Test
    void zeroDeltaOfShareKeepsOneDecimalPlaceAndSameTrend() throws Exception {
        assertThat(metricJson(MetricKind.CLEAN_GOALS_SHARE, "62.7", "62.7"))
                .contains("\"delta\":0.0,\"trend\":\"same\"");
    }

    @Test
    void counterHasNoFractionalPart() throws Exception {
        assertThat(metricJson(MetricKind.ISSUES_TOTAL, "0", "0"))
                .contains("\"value\":0,\"previous\":0,\"delta\":0,\"trend\":\"same\"");
    }

    @Test
    void trendIsLowerCase() throws Exception {
        assertThat(metricJson(MetricKind.ISSUES_TOTAL, "5", "9")).contains("\"trend\":\"better\"");
        assertThat(metricJson(MetricKind.ISSUES_TOTAL, "9", "5")).contains("\"trend\":\"worse\"");
        assertThat(metricJson(MetricKind.ISSUES_TOTAL, "5", "5")).contains("\"trend\":\"same\"");
    }

    @Test
    void shareWithoutPreviousKeepsNullFields() throws Exception {
        assertThat(json(SummaryResponse.Metric.from(
                        MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, decimal("50.0"), null))))
                .isEqualTo("""
                        {"key":"clean_goals_share","title":"Доля корректных целей","unit":"%",\
                        "value":50.0,"previous":null,"delta":null,"trend":null,"higher_is_better":true}""");
    }

    @Test
    void shareWithoutCurrentValueKeepsNullFields() throws Exception {
        assertThat(json(SummaryResponse.Metric.from(
                        MetricComparison.of(MetricKind.CASCADE_COVERAGE, null, decimal("75.0")))))
                .isEqualTo("""
                        {"key":"cascade_coverage","title":"Покрытие каскадом","unit":"%",\
                        "value":null,"previous":75.0,"delta":null,"trend":null,"higher_is_better":true}""");
    }

    @Test
    void severityRowWithZeroPreviousHasNumberAndNotNull() throws Exception {
        assertThat(json(SummaryResponse.SeverityRow.from(new SeverityCount(Severity.MAJOR, 3, 0, 3))))
                .isEqualTo("""
                        {"code":"major","current":3,"previous":0,"delta":3}""");
    }

    @Test
    void deltaKeepsKeyNewAndZeroCounters() throws Exception {
        assertThat(json(SummaryResponse.Delta.from(new DeltaCounts(0, 0, 0)))).isEqualTo("""
                {"new":0,"persisting":0,"resolved":0}""");
    }

    @Test
    void deltaWithoutBaseKeepsThreeNulls() throws Exception {
        assertThat(json(SummaryResponse.Delta.from(new DeltaCounts(null, null, null)))).isEqualTo("""
                {"new":null,"persisting":null,"resolved":null}""");
    }

    @Test
    void runRefHasOnlyIdAndDate() throws Exception {
        Run withOtherFields = new Run("RUN-008", DATE, RunStatus.COMPLETED, "RUN-007", "старый текст");

        assertThat(json(SummaryResponse.RunRef.from(withOtherFields))).isEqualTo("""
                {"id":"RUN-008","snapshot_date":"2026-10-26"}""");
    }

    @Test
    void unitRefHasOnlyIdAndName() throws Exception {
        assertThat(json(SummaryResponse.UnitRef.from(new Unit("CIB-NORTH", "Север", "HQ-CIB")))).isEqualTo("""
                {"id":"CIB-NORTH","name":"Север"}""");
    }

    // GET /api/kkd/issues, раздел 8.4

    @Test
    void issueListMatchesExampleOfSection84() throws Exception {
        Issue issue = new Issue(
                RuleCode.KR_STALE_PROGRESS, "KR-11-1", Issue.OBJECT_TYPE_KEY_RESULT, Severity.MINOR,
                "G-11", "CIB-NORTH", "Факт не обновлялся 51 дн.");
        IssuePage page = new IssuePage(
                "RUN-008",
                List.of(new IssuePage.Item(issue, "Подписать соглашения с банками-участниками синдикатов")),
                18,
                1,
                0);

        assertThat(json(IssueListResponse.from(page))).isEqualTo("""
                {"run_id":"RUN-008",\
                "items":[\
                {"id":"KR_STALE_PROGRESS:KR-11-1","rule_code":"KR_STALE_PROGRESS","severity":"minor",\
                "object_type":"key_result","object_id":"KR-11-1","goal_id":"G-11",\
                "goal_title":"Подписать соглашения с банками-участниками синдикатов","unit_id":"CIB-NORTH",\
                "details":"Факт не обновлялся 51 дн."}\
                ],\
                "total":18,"limit":1,"offset":0}""");
    }

    @Test
    void goalIssueHasObjectTypeGoalAndIdFromRuleAndObject() throws Exception {
        Issue issue = new Issue(
                RuleCode.GOAL_NO_KR, "G-09", Issue.OBJECT_TYPE_GOAL, Severity.CRITICAL, "G-09", "CIB-NORTH", "Нет КР");

        assertThat(json(IssueListResponse.Item.from(new IssuePage.Item(issue, "Цель")))).isEqualTo("""
                {"id":"GOAL_NO_KR:G-09","rule_code":"GOAL_NO_KR","severity":"critical",\
                "object_type":"goal","object_id":"G-09","goal_id":"G-09",\
                "goal_title":"Цель","unit_id":"CIB-NORTH","details":"Нет КР"}""");
    }

    @Test
    void emptyIssueListKeepsEmptyItems() throws Exception {
        assertThat(json(IssueListResponse.from(new IssuePage("RUN-008", List.of(), 0, 20, 0)))).isEqualTo("""
                {"run_id":"RUN-008","items":[],"total":0,"limit":20,"offset":0}""");
    }

    @Test
    void offsetBehindEndKeepsTotal() throws Exception {
        assertThat(json(IssueListResponse.from(new IssuePage("RUN-008", List.of(), 7, 5, 1000)))).isEqualTo("""
                {"run_id":"RUN-008","items":[],"total":7,"limit":5,"offset":1000}""");
    }

    // Единый формат ошибок, раздел 8.5

    @Test
    void errorMatchesExampleOfSection85() throws Exception {
        assertThat(json(ApiErrorResponse.of("INVALID_PARAM", "Параметр limit должен быть целым числом от 1 до 200")))
                .isEqualTo("""
                        {"error":{"code":"INVALID_PARAM","message":"Параметр limit должен быть целым числом от 1 до 200"}}""");
    }

    private String metricJson(MetricKind kind, String value, String previous) throws JsonProcessingException {
        return json(SummaryResponse.Metric.from(MetricComparison.of(kind, decimal(value), decimal(previous))));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
