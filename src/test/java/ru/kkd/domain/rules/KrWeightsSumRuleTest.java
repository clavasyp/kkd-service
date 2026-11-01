package ru.kkd.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.kkd.domain.model.GoalLevel;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.KeyResultRow;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;
import ru.kkd.domain.model.Snapshot;

class KrWeightsSumRuleTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 26);

    // В справочнике у правила major. Другая важность проверяет, что правило берёт переданную.
    private static final Severity SEVERITY = Severity.MINOR;

    private static final GoalRow GOAL = goal("G-07");

    private final KrWeightsSumRule rule = new KrWeightsSumRule();

    @Test
    void weight100IsValid() {
        assertThat(checkWeights("100")).isEmpty();
    }

    @Test
    void weight100WithDecimalsIsValid() {
        assertThat(checkWeights("100.00")).isEmpty();
    }

    @Test
    void weights33_4_33_3_33_3AreValid() {
        assertThat(checkWeights("33.4", "33.3", "33.3")).isEmpty();
    }

    @Test
    void weights23_7_69_4_6_9AreValid() {
        assertThat(checkWeights("23.7", "69.4", "6.9")).isEmpty();
    }

    @Test
    void zeroWeightCountsAsNumber() {
        assertThat(checkWeights("100.00", "0.00")).isEmpty();
    }

    @Test
    void negativeWeightCountsAsNumber() {
        assertThat(checkWeights("120.00", "-20.00")).isEmpty();
    }

    @Test
    void sum99_99Fires() {
        assertThat(checkWeights("99.99"))
                .containsExactly(expectedIssue("Сумма весов КР равна 99.99, а должна быть 100"));
    }

    @Test
    void sum100_01Fires() {
        assertThat(checkWeights("100.01"))
                .containsExactly(expectedIssue("Сумма весов КР равна 100.01, а должна быть 100"));
    }

    // В базе у веса два знака, но допуска нет и у самого правила: меньше 0.01 тоже нарушение.
    @Test
    void sum99_995Fires() {
        assertThat(checkWeights("99.995"))
                .containsExactly(expectedIssue("Сумма весов КР равна 99.995, а должна быть 100"));
    }

    @Test
    void sum100_005Fires() {
        assertThat(checkWeights("100.005"))
                .containsExactly(expectedIssue("Сумма весов КР равна 100.005, а должна быть 100"));
    }

    @Test
    void weights33_33ThreeTimesFire() {
        assertThat(checkWeights("33.33", "33.33", "33.33"))
                .containsExactly(expectedIssue("Сумма весов КР равна 99.99, а должна быть 100"));
    }

    @Test
    void weights33_3ThreeTimesFireAndSumHasNoTrailingZero() {
        assertThat(checkWeights("33.30", "33.30", "33.30"))
                .containsExactly(expectedIssue("Сумма весов КР равна 99.9, а должна быть 100"));
    }

    @Test
    void singleKeyResultWithWeight50Fires() {
        assertThat(checkWeights("50.00"))
                .containsExactly(expectedIssue("Сумма весов КР равна 50, а должна быть 100"));
    }

    @Test
    void weight100PlusEmptyWeightFires() {
        assertThat(checkWeights("100.00", null))
                .containsExactly(expectedIssue("Сумма заполненных весов КР равна 100, есть КР с пустым весом"));
    }

    @Test
    void onlyEmptyWeightFires() {
        assertThat(checkWeights((String) null))
                .containsExactly(expectedIssue("Сумма заполненных весов КР равна 0, есть КР с пустым весом"));
    }

    @Test
    void goalWithoutKeyResultsDoesNotFire() {
        assertThat(checkWeights()).isEmpty();
    }

    @Test
    void oneIssuePerGoalAndObjectIsGoal() {
        GoalRow validGoal = goal("G-05");
        List<KeyResultRow> keyResults = List.of(
                keyResult("KR-07-1", "G-07", "60.00"),
                keyResult("KR-07-2", "G-07", "30.00"),
                keyResult("KR-05-1", "G-05", "50.00"),
                keyResult("KR-05-2", "G-05", "50.00"));
        List<GoalRow> goals = List.of(GOAL, validGoal);

        List<Issue> issues = rule.check(new Snapshot(SNAPSHOT_DATE, goals, keyResults), goals, SEVERITY);

        assertThat(issues).containsExactly(new Issue(
                RuleCode.KR_WEIGHTS_SUM, "G-07", "goal", SEVERITY, "G-07", "CIB-NORTH",
                "Сумма весов КР равна 90, а должна быть 100"));
    }

    private List<Issue> checkWeights(String... weights) {
        List<KeyResultRow> keyResults = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            keyResults.add(keyResult("KR-07-" + (i + 1), GOAL.id(), weights[i]));
        }
        Snapshot snapshot = new Snapshot(SNAPSHOT_DATE, List.of(GOAL), keyResults);
        return rule.check(snapshot, List.of(GOAL), SEVERITY);
    }

    private static GoalRow goal(String id) {
        return new GoalRow(id, "Цель", "CIB-NORTH", GoalLevel.UNIT, "Васильев К. М.", "G-02", "active", null);
    }

    private static KeyResultRow keyResult(String id, String goalId, String weight) {
        return new KeyResultRow(
                id, goalId, "КР", "шт.", BigDecimal.TEN, BigDecimal.ONE,
                weight == null ? null : new BigDecimal(weight), SNAPSHOT_DATE);
    }

    private static Issue expectedIssue(String details) {
        return new Issue(RuleCode.KR_WEIGHTS_SUM, GOAL.id(), "goal", SEVERITY, GOAL.id(), GOAL.unitId(), details);
    }
}
