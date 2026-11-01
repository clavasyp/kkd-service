package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import ru.kkd.service.IssueQuery;
import ru.kkd.service.IssueService;
import ru.kkd.service.SummaryQuery;
import ru.kkd.service.SummaryService;

/** Рабочий путь summary и issues через MockMvc: параметры доходят до сервиса, ответ приходит формой раздела 8. */
@WebMvcTest(KkdController.class)
class KkdControllerWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SummaryService summaryService;

    @MockitoBean
    private IssueService issueService;

    // summary

    @Test
    void summaryPassesParametersToService() throws Exception {
        when(summaryService.getSummary(any())).thenReturn(firstRunSummary());

        bodyOf(get("/api/kkd/summary").param("run_id", "RUN-007").param("unit_id", "HQ-CIB"));

        verify(summaryService).getSummary(new SummaryQuery("RUN-007", "HQ-CIB"));
    }

    @Test
    void summaryAnswersWithFormOfSection83() throws Exception {
        when(summaryService.getSummary(any())).thenReturn(firstRunSummary());

        assertThat(bodyOf(get("/api/kkd/summary").param("run_id", "RUN-007").param("unit_id", "HQ-CIB"))).isEqualTo("""
                {"run":{"id":"RUN-007","snapshot_date":"2026-10-19"},\
                "compared_to":null,\
                "unit":{"id":"HQ-CIB","name":"Департамент корпоративного бизнеса"},\
                "metrics":[\
                {"key":"issues_total","title":"Найдено проблем","unit":"шт.",\
                "value":35,"previous":null,"delta":null,"trend":null,"higher_is_better":false}\
                ],\
                "issues_by_severity":[{"code":"critical","current":2,"previous":null,"delta":null}],\
                "delta":{"new":null,"persisting":null,"resolved":null}}""");
    }

    @Test
    void summaryIgnoresUnknownParameters() throws Exception {
        when(summaryService.getSummary(any())).thenReturn(firstRunSummary());

        bodyOf(get("/api/kkd/summary")
                .param("run_id", "RUN-007")
                .param("unit_id", "HQ-CIB")
                .param("limit", "abc")
                .param("foo", "bar"));

        verify(summaryService).getSummary(new SummaryQuery("RUN-007", "HQ-CIB"));
    }

    // issues

    @Test
    void issuesWithoutOptionalParametersGetDefaults() throws Exception {
        when(issueService.findIssues(any())).thenReturn(new IssuePage("RUN-008", List.of(), 0, 20, 0));

        assertThat(bodyOf(get("/api/kkd/issues").param("run_id", "RUN-008"))).isEqualTo("""
                {"run_id":"RUN-008","items":[],"total":0,"limit":20,"offset":0}""");

        verify(issueService).findIssues(new IssueQuery("RUN-008", null, null, null, 20, 0));
    }

    @Test
    void issuesPassAllParametersToService() throws Exception {
        when(issueService.findIssues(any())).thenReturn(new IssuePage("RUN-008", List.of(), 0, 5, 10));

        bodyOf(get("/api/kkd/issues")
                .param("run_id", "RUN-008")
                .param("unit_id", "HQ-CIB")
                .param("rule_code", "GOAL_NO_OWNER")
                .param("severity", "critical")
                .param("limit", "5")
                .param("offset", "10"));

        verify(issueService).findIssues(
                new IssueQuery("RUN-008", "HQ-CIB", RuleCode.GOAL_NO_OWNER, Severity.CRITICAL, 5, 10));
    }

    @Test
    void issuesAnswerWithFormOfSection84() throws Exception {
        Issue issue = new Issue(
                RuleCode.KR_STALE_PROGRESS, "KR-11-1", Issue.OBJECT_TYPE_KEY_RESULT, Severity.MINOR,
                "G-11", "CIB-NORTH", "Факт не обновлялся 51 дн.");
        when(issueService.findIssues(any())).thenReturn(new IssuePage(
                "RUN-008",
                List.of(new IssuePage.Item(issue, "Подписать соглашения с банками-участниками синдикатов")),
                18,
                1,
                0));

        assertThat(bodyOf(get("/api/kkd/issues").param("run_id", "RUN-008").param("limit", "1"))).isEqualTo("""
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
    void issuesIgnoreUnknownParameters() throws Exception {
        when(issueService.findIssues(any())).thenReturn(new IssuePage("RUN-008", List.of(), 0, 20, 0));

        bodyOf(get("/api/kkd/issues").param("run_id", "RUN-008").param("foo", "bar"));

        verify(issueService).findIssues(new IssueQuery("RUN-008", null, null, null, 20, 0));
    }

    private String bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static Summary firstRunSummary() {
        return new Summary(
                new Run("RUN-007", LocalDate.of(2026, 10, 19), RunStatus.COMPLETED, null, null),
                null,
                new Unit("HQ-CIB", "Департамент корпоративного бизнеса", null),
                List.of(MetricComparison.of(MetricKind.ISSUES_TOTAL, new BigDecimal("35"), null)),
                List.of(new SeverityCount(Severity.CRITICAL, 2, null, null)),
                new DeltaCounts(null, null, null));
    }
}
