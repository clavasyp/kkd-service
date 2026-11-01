package ru.kkd.api;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.stream.Collectors;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.service.IssueQuery;
import ru.kkd.service.KkdException;
import ru.kkd.service.SummaryQuery;

/**
 * Строки запроса в типы: раздел 8.1 design.md. Только этот класс решает, корректны ли значения параметров.
 * null значит, что параметра в запросе нет. Первая найденная ошибка даёт KkdException с кодом INVALID_PARAM,
 * параметры проверяются в порядке объявления в методе.
 */
public final class RequestParser {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_OFFSET = 0;

    private RequestParser() {
    }

    public static SummaryQuery parseSummary(String runId, String unitId) {
        return new SummaryQuery(requiredId("run_id", runId), requiredId("unit_id", unitId));
    }

    public static IssueQuery parseIssues(
            String runId, String unitId, String ruleCode, String severity, String limit, String offset) {
        return new IssueQuery(
                requiredId("run_id", runId),
                unitId == null ? null : id("unit_id", unitId),
                ruleCode == null ? null : ruleCode(ruleCode),
                severity == null ? null : severity(severity),
                limit == null ? DEFAULT_LIMIT : number("limit", limit, 1, MAX_LIMIT),
                offset == null ? DEFAULT_OFFSET : number("offset", offset, 0, Integer.MAX_VALUE));
    }

    private static String requiredId(String name, String value) {
        if (value == null) {
            throw KkdException.invalidParam("Параметр " + name + " обязателен");
        }
        return id(name, value);
    }

    private static String id(String name, String value) {
        if (value.isEmpty()) {
            throw KkdException.invalidParam("Параметр " + name + " не должен быть пустым");
        }
        // Нулевой символ база не примет даже в условии поиска, и такого идентификатора быть не может.
        if (value.indexOf('\0') >= 0) {
            throw KkdException.invalidParam("Параметр " + name + " содержит недопустимый символ");
        }
        return value;
    }

    private static RuleCode ruleCode(String value) {
        return Arrays.stream(RuleCode.values())
                .filter(code -> code.name().equals(value))
                .findFirst()
                .orElseThrow(() -> KkdException.invalidParam(
                        "Параметр rule_code должен быть одним из значений: " + names(RuleCode.values())));
    }

    private static Severity severity(String value) {
        try {
            return EnumText.parse(Severity.class, value);
        } catch (IllegalArgumentException e) {
            throw KkdException.invalidParam(
                    "Параметр severity должен быть одним из значений: " + texts(Severity.values()));
        }
    }

    // Integer.parseInt здесь не подходит: он принимает знак и цифры других алфавитов.
    private static int number(String name, String value, int min, int max) {
        if (value.matches("[0-9]+")) {
            BigInteger number = new BigInteger(value);
            if (number.compareTo(BigInteger.valueOf(min)) >= 0 && number.compareTo(BigInteger.valueOf(max)) <= 0) {
                return number.intValue();
            }
        }
        throw KkdException.invalidParam("Параметр " + name + " должен быть целым числом от " + min + " до " + max);
    }

    private static String names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static String texts(Enum<?>[] values) {
        return Arrays.stream(values).map(EnumText::format).collect(Collectors.joining(", "));
    }
}
