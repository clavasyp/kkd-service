package ru.kkd.api;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.Globals;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.kkd.service.IssueService;
import ru.kkd.service.KkdException;
import ru.kkd.service.SummaryService;

/** Параметры приходят строками: их значения проверяет RequestParser, а не Spring. Раздел 8 design.md. */
@RestController
public class KkdController {

    private final SummaryService summaryService;
    private final IssueService issueService;

    public KkdController(SummaryService summaryService, IssueService issueService) {
        this.summaryService = summaryService;
        this.issueService = issueService;
    }

    /** Tomcat молча отбрасывает параметр с испорченным кодированием: без этой проверки severity=%zz дал бы весь список. */
    @ModelAttribute
    void rejectBrokenQuery(HttpServletRequest request) {
        // Тот же признак Tomcat ставит и куску без имени, например =1. Причину он хранит только первую, поэтому
        // отвергается любая ошибка разбора: иначе в =1&severity=%zz фильтр severity тоже молча пропал бы. Раздел 8.1.
        request.getParameterMap();
        if (request.getAttribute(Globals.PARAMETER_PARSE_FAILED_ATTR) != null) {
            throw KkdException.invalidParam("Строка запроса содержит испорченное кодирование или параметр без имени");
        }
    }

    @GetMapping("/api/kkd/summary")
    public SummaryResponse summary(
            @RequestParam(name = "run_id", required = false) String runId,
            @RequestParam(name = "unit_id", required = false) String unitId) {
        return SummaryResponse.from(summaryService.getSummary(RequestParser.parseSummary(runId, unitId)));
    }

    @GetMapping("/api/kkd/issues")
    public IssueListResponse issues(
            @RequestParam(name = "run_id", required = false) String runId,
            @RequestParam(name = "unit_id", required = false) String unitId,
            @RequestParam(name = "rule_code", required = false) String ruleCode,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "offset", required = false) String offset) {
        return IssueListResponse.from(issueService.findIssues(
                RequestParser.parseIssues(runId, unitId, ruleCode, severity, limit, offset)));
    }
}
