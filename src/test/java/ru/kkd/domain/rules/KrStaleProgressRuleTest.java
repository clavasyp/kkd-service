package ru.kkd.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

class KrStaleProgressRuleTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);

    // В справочнике у правила minor. Другая важность проверяет, что правило берёт переданную.
    private static final Severity SEVERITY = Severity.CRITICAL;

    private static final GoalRow GOAL = new GoalRow(
            "G-11", "Цель", "CIB-NORTH", GoalLevel.EMPLOYEE, "Морозов С. П.", "G-06", "active", null);

    private final KrStaleProgressRule rule = new KrStaleProgressRule();

    @Test
    void factUpdated45DaysAgoIsNotStale() {
        List<Issue> issues = check(SNAPSHOT_DATE, keyResult("KR-11-1", SNAPSHOT_DATE.minusDays(45)));

        assertThat(issues).isEmpty();
    }

    @Test
    void factUpdated46DaysAgoIsStale() {
        List<Issue> issues = check(SNAPSHOT_DATE, keyResult("KR-11-1", SNAPSHOT_DATE.minusDays(46)));

        assertThat(issues).containsExactly(expectedIssue("KR-11-1", "Факт не обновлялся 46 дн."));
    }

    @Test
    void emptyFactDateIsNotStale() {
        List<Issue> issues = check(SNAPSHOT_DATE, keyResult("KR-11-1", null));

        assertThat(issues).isEmpty();
    }

    @Test
    void factDateInFutureIsNotStale() {
        // Дальше 45 дней вперёд: возраст, взятый по модулю, дал бы ложное нарушение.
        List<Issue> issues = check(SNAPSHOT_DATE, keyResult("KR-11-1", SNAPSHOT_DATE.plusDays(100)));

        assertThat(issues).isEmpty();
    }

    @Test
    void ageIsCountedFromSnapshotDate() {
        KeyResultRow keyResult = keyResult("KR-11-1", LocalDate.of(2026, 9, 10));

        assertThat(check(LocalDate.of(2026, 10, 19), keyResult)).isEmpty();
        assertThat(check(LocalDate.of(2026, 10, 26), keyResult))
                .containsExactly(expectedIssue("KR-11-1", "Факт не обновлялся 46 дн."));
    }

    @Test
    void issuePointsToKeyResultAndCarriesGoalAndUnit() {
        KeyResultRow fresh = keyResult("KR-11-2", SNAPSHOT_DATE);
        KeyResultRow stale = keyResult("KR-11-1", LocalDate.of(2026, 9, 5));

        List<Issue> issues = check(SNAPSHOT_DATE, fresh, stale);

        assertThat(issues).containsExactly(new Issue(
                RuleCode.KR_STALE_PROGRESS, "KR-11-1", "key_result", SEVERITY,
                "G-11", "CIB-NORTH", "Факт не обновлялся 51 дн."));
    }

    private List<Issue> check(LocalDate snapshotDate, KeyResultRow... keyResults) {
        Snapshot snapshot = new Snapshot(snapshotDate, List.of(GOAL), List.of(keyResults));
        return rule.check(snapshot, List.of(GOAL), SEVERITY);
    }

    private static KeyResultRow keyResult(String id, LocalDate factUpdatedOn) {
        return new KeyResultRow(
                id, GOAL.id(), "КР", "шт.", BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("100.00"), factUpdatedOn);
    }

    private static Issue expectedIssue(String keyResultId, String details) {
        return new Issue(
                RuleCode.KR_STALE_PROGRESS, keyResultId, "key_result", SEVERITY, GOAL.id(), GOAL.unitId(), details);
    }
}
