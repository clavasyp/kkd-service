package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.service.IssueQuery;
import ru.kkd.service.KkdException;
import ru.kkd.service.SummaryQuery;

/**
 * Параметры раздела 8.1 design.md. null значит, что параметра в запросе нет.
 * Нулевой символ и перевод строки не попадают в имя теста: в отчёте Surefire им не место.
 */
class RequestParserTest {

    private static final String RULE_CODE_MESSAGE = "Параметр rule_code должен быть одним из значений: "
            + "GOAL_NO_OWNER, GOAL_NO_KR, KR_WEIGHTS_SUM, CASCADE_NO_PARENT, KR_NO_TARGET, KR_STALE_PROGRESS";
    private static final String SEVERITY_MESSAGE = "Параметр severity должен быть одним из значений: critical, major, minor";
    private static final String LIMIT_MESSAGE = "Параметр limit должен быть целым числом от 1 до 200";
    private static final String OFFSET_MESSAGE = "Параметр offset должен быть целым числом от 0 до 2147483647";

    // Постоянные значения

    @Test
    void limitsAreFromSection81() {
        assertThat(RequestParser.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(RequestParser.MAX_LIMIT).isEqualTo(200);
        assertThat(RequestParser.DEFAULT_OFFSET).isZero();
    }

    // summary: верные значения

    @Test
    void summaryKeepsBothValues() {
        assertThat(RequestParser.parseSummary("RUN-008", "HQ-CIB")).isEqualTo(new SummaryQuery("RUN-008", "HQ-CIB"));
    }

    @Test
    void identifierWithBadFormIsStillValidForParser() {
        // Граница 404 и 422: значение abc допустимо по форме, такого прогона просто нет.
        assertThat(RequestParser.parseSummary("abc", "abc")).isEqualTo(new SummaryQuery("abc", "abc"));
    }

    @Test
    void spaceIsValidFormOfIdentifierAndIsNotTrimmed() {
        assertThat(RequestParser.parseSummary(" ", " RUN ")).isEqualTo(new SummaryQuery(" ", " RUN "));
    }

    // summary: run_id и unit_id

    @Test
    void summaryRequiresRunId() {
        assertInvalid(() -> RequestParser.parseSummary(null, "HQ-CIB"), "Параметр run_id обязателен");
    }

    @Test
    void summaryRejectsEmptyRunId() {
        assertInvalid(() -> RequestParser.parseSummary("", "HQ-CIB"), "Параметр run_id не должен быть пустым");
    }

    @Test
    void summaryRequiresUnitId() {
        assertInvalid(() -> RequestParser.parseSummary("RUN-008", null), "Параметр unit_id обязателен");
    }

    @Test
    void summaryRejectsEmptyUnitId() {
        assertInvalid(() -> RequestParser.parseSummary("RUN-008", ""), "Параметр unit_id не должен быть пустым");
    }

    @Test
    void summaryRejectsNullCharInRunId() {
        String message = "Параметр run_id содержит недопустимый символ";
        assertInvalid(() -> RequestParser.parseSummary("\0", "HQ-CIB"), message);
        assertInvalid(() -> RequestParser.parseSummary("RUN\0-008", "HQ-CIB"), message);
        assertInvalid(() -> RequestParser.parseSummary("RUN-008\0", "HQ-CIB"), message);
    }

    @Test
    void summaryRejectsNullCharInUnitId() {
        String message = "Параметр unit_id содержит недопустимый символ";
        assertInvalid(() -> RequestParser.parseSummary("RUN-008", "\0"), message);
        assertInvalid(() -> RequestParser.parseSummary("RUN-008", "HQ\0-CIB"), message);
    }

    @Test
    void summaryChecksRunIdBeforeUnitId() {
        assertInvalid(() -> RequestParser.parseSummary(null, null), "Параметр run_id обязателен");
        assertInvalid(() -> RequestParser.parseSummary("", ""), "Параметр run_id не должен быть пустым");
    }

    // issues: значения по умолчанию

    @Test
    void issuesWithRunIdOnlyGetDefaults() {
        assertThat(parseIssues("RUN-008", null, null, null, null, null))
                .isEqualTo(new IssueQuery("RUN-008", null, null, null, 20, 0));
    }

    @Test
    void issuesKeepAllValues() {
        assertThat(parseIssues("RUN-008", "HQ-CIB", "KR_STALE_PROGRESS", "minor", "5", "10"))
                .isEqualTo(new IssueQuery("RUN-008", "HQ-CIB", RuleCode.KR_STALE_PROGRESS, Severity.MINOR, 5, 10));
    }

    @Test
    void limitIsDefaultWhenOnlyOffsetIsGiven() {
        assertThat(parseIssues("RUN-008", null, null, null, null, "5").limit()).isEqualTo(20);
    }

    @Test
    void offsetIsDefaultWhenOnlyLimitIsGiven() {
        assertThat(parseIssues("RUN-008", null, null, null, "5", null).offset()).isZero();
    }

    // issues: run_id и unit_id

    @Test
    void issuesRequireRunId() {
        assertInvalid(() -> parseIssues(null, null, null, null, null, null), "Параметр run_id обязателен");
    }

    @Test
    void issuesRejectEmptyRunId() {
        assertInvalid(() -> parseIssues("", null, null, null, null, null), "Параметр run_id не должен быть пустым");
    }

    @Test
    void issuesRejectNullCharInRunId() {
        assertInvalid(() -> parseIssues("RUN\0", null, null, null, null, null),
                "Параметр run_id содержит недопустимый символ");
    }

    @Test
    void issuesAcceptSpaceInIdentifiers() {
        IssueQuery query = parseIssues(" ", " ", null, null, null, null);

        assertThat(query.runId()).isEqualTo(" ");
        assertThat(query.unitId()).isEqualTo(" ");
    }

    @Test
    void issuesKeepMissingUnitIdAsNull() {
        assertThat(parseIssues("RUN-008", null, null, null, null, null).unitId()).isNull();
    }

    @Test
    void issuesRejectEmptyUnitId() {
        assertInvalid(() -> parseIssues("RUN-008", "", null, null, null, null), "Параметр unit_id не должен быть пустым");
    }

    @Test
    void issuesRejectNullCharInUnitId() {
        assertInvalid(() -> parseIssues("RUN-008", "\0", null, null, null, null),
                "Параметр unit_id содержит недопустимый символ");
    }

    // issues: rule_code

    @ParameterizedTest
    @EnumSource(RuleCode.class)
    void everyRuleCodeIsAccepted(RuleCode code) {
        assertThat(parseIssues("RUN-008", null, code.name(), null, null, null).ruleCode()).isEqualTo(code);
    }

    @Test
    void thereAreExactlySixRuleCodes() {
        assertThat(RuleCode.values()).hasSize(6);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
            "", " ", "NOPE", "goal_no_kr", "Goal_No_Kr", " GOAL_NO_KR", "GOAL_NO_KR ", "GOAL-NO-KR",
            "GOAL_NO_KR,KR_WEIGHTS_SUM", "CASCADE_WRONG_LEVEL"})
    void otherRuleCodesAreRejected(String code) {
        assertInvalid(() -> parseIssues("RUN-008", null, code, null, null, null), RULE_CODE_MESSAGE);
    }

    @Test
    void ruleCodeWithNullCharIsRejected() {
        assertInvalid(() -> parseIssues("RUN-008", null, "GOAL_NO_KR\0", null, null, null), RULE_CODE_MESSAGE);
    }

    // issues: severity

    @ParameterizedTest
    @CsvSource({"critical, CRITICAL", "major, MAJOR", "minor, MINOR"})
    void everySeverityIsAccepted(String text, Severity expected) {
        assertThat(parseIssues("RUN-008", null, null, text, null, null).severity()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "CRITICAL", "Critical", "MINOR", " minor", "minor ", "blocker", "critical,major"})
    void otherSeveritiesAreRejected(String severity) {
        assertInvalid(() -> parseIssues("RUN-008", null, null, severity, null, null), SEVERITY_MESSAGE);
    }

    // issues: limit

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "20, 20", "199, 199", "200, 200", "007, 7", "0200, 200",
            "00000000000000000000000000000001, 1"})
    void validLimitsAreAccepted(String text, int expected) {
        assertThat(parseIssues("RUN-008", null, null, null, text, null).limit()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
            "", " ", "abc", "0", "00", "-1", "-0", "201", "0201", "00000000000000000000000000000201",
            "+5", " 5", "5 ", "5 5", "1.0", "1,0", "1e2", "0x10", "5a",
            "2147483647", "2147483648", "4294967297", "99999999999999999999",
            "٣", "５", "५", "1٠"})
    void invalidLimitsAreRejected(String limit) {
        assertInvalid(() -> parseIssues("RUN-008", null, null, null, limit, null), LIMIT_MESSAGE);
    }

    @Test
    void limitWithControlCharsIsRejected() {
        for (String limit : new String[] {"5\n", "\n5", "5\t", "5\0"}) {
            assertInvalid(() -> parseIssues("RUN-008", null, null, null, limit, null), LIMIT_MESSAGE);
        }
    }

    // issues: offset

    @ParameterizedTest
    @CsvSource({"0, 0", "00, 0", "1, 1", "1000, 1000", "007, 7", "2147483647, 2147483647",
            "0002147483647, 2147483647"})
    void validOffsetsAreAccepted(String text, int expected) {
        assertThat(parseIssues("RUN-008", null, null, null, null, text).offset()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
            "", " ", "abc", "-1", "-0", "+5", "+0", " 5", "5 ", "1.0", "1e2", "0x10",
            "2147483648", "0002147483648", "4294967296", "99999999999999999999",
            "٣", "５", "५"})
    void invalidOffsetsAreRejected(String offset) {
        assertInvalid(() -> parseIssues("RUN-008", null, null, null, null, offset), OFFSET_MESSAGE);
    }

    @Test
    void offsetWithControlCharsIsRejected() {
        for (String offset : new String[] {"5\n", "\n5", "5\t", "5\0"}) {
            assertInvalid(() -> parseIssues("RUN-008", null, null, null, null, offset), OFFSET_MESSAGE);
        }
    }

    // Первая найденная ошибка: порядок run_id, unit_id, rule_code, severity, limit, offset

    @Test
    void firstErrorFollowsOrderOfParameters() {
        assertInvalid(() -> parseIssues(null, "", "x", "x", "x", "x"), "Параметр run_id обязателен");
        assertInvalid(() -> parseIssues("", "", "x", "x", "x", "x"), "Параметр run_id не должен быть пустым");
        assertInvalid(() -> parseIssues("RUN-008", "", "x", "x", "x", "x"), "Параметр unit_id не должен быть пустым");
        assertInvalid(() -> parseIssues("RUN-008", "HQ-CIB", "x", "x", "x", "x"), RULE_CODE_MESSAGE);
        assertInvalid(() -> parseIssues("RUN-008", "HQ-CIB", "GOAL_NO_KR", "x", "x", "x"), SEVERITY_MESSAGE);
        assertInvalid(() -> parseIssues("RUN-008", "HQ-CIB", "GOAL_NO_KR", "minor", "x", "x"), LIMIT_MESSAGE);
        assertInvalid(() -> parseIssues("RUN-008", "HQ-CIB", "GOAL_NO_KR", "minor", "1", "x"), OFFSET_MESSAGE);
    }

    @Test
    void missingUnitIdDoesNotHideLaterErrors() {
        assertInvalid(() -> parseIssues("RUN-008", null, "x", null, null, null), RULE_CODE_MESSAGE);
    }

    private static IssueQuery parseIssues(
            String runId, String unitId, String ruleCode, String severity, String limit, String offset) {
        return RequestParser.parseIssues(runId, unitId, ruleCode, severity, limit, offset);
    }

    private static void assertInvalid(ThrowingCallable parse, String message) {
        KkdException error = catchThrowableOfType(KkdException.class, parse);

        assertThat(error).as("ожидалась ошибка: %s", message).isNotNull();
        assertThat(error.code()).isEqualTo(KkdException.INVALID_PARAM);
        assertThat(error.getMessage()).isEqualTo(message);
    }
}
