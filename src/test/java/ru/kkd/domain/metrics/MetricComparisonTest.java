package ru.kkd.domain.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class MetricComparisonTest {

    @Test
    void exampleFromTaskGivesExactlyMinus6_2AndWorse() {
        BigDecimal value = new BigDecimal("56.5");
        BigDecimal previous = new BigDecimal("62.7");

        MetricComparison comparison = MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, value, previous);

        assertThat(comparison).isEqualTo(new MetricComparison(
                MetricKind.CLEAN_GOALS_SHARE, value, previous, new BigDecimal("-6.2"), Trend.WORSE));
    }

    @ParameterizedTest
    @CsvSource({
        "CLEAN_GOALS_SHARE, 62.7, 56.5, 6.2, BETTER",
        "CLEAN_GOALS_SHARE, 56.5, 62.7, -6.2, WORSE",
        "CASCADE_COVERAGE, 80.0, 75.0, 5.0, BETTER",
        "CASCADE_COVERAGE, 75.0, 80.0, -5.0, WORSE",
        "ISSUES_TOTAL, 43, 35, 8, WORSE",
        "ISSUES_TOTAL, 35, 43, -8, BETTER"
    })
    void trendFollowsDirectionOfMetric(
            MetricKind kind, BigDecimal value, BigDecimal previous, BigDecimal delta, Trend trend) {
        assertThat(MetricComparison.of(kind, value, previous))
                .isEqualTo(new MetricComparison(kind, value, previous, delta, trend));
    }

    @ParameterizedTest
    @CsvSource({
        "CLEAN_GOALS_SHARE, 33.3, 33.3, 0.0",
        "CASCADE_COVERAGE, 100.0, 100.0, 0.0",
        "ISSUES_TOTAL, 2, 2, 0"
    })
    void zeroDeltaIsSame(MetricKind kind, BigDecimal value, BigDecimal previous, BigDecimal delta) {
        assertThat(MetricComparison.of(kind, value, previous))
                .isEqualTo(new MetricComparison(kind, value, previous, delta, Trend.SAME));
    }

    @ParameterizedTest
    @EnumSource(MetricKind.class)
    void noPreviousGivesNullDeltaAndTrendAndKeepsValue(MetricKind kind) {
        BigDecimal value = new BigDecimal("56.5");

        assertThat(MetricComparison.of(kind, value, null))
                .isEqualTo(new MetricComparison(kind, value, null, null, null));
    }

    @ParameterizedTest
    @EnumSource(MetricKind.class)
    void noValueGivesNullDeltaAndTrendAndKeepsPrevious(MetricKind kind) {
        BigDecimal previous = new BigDecimal("62.7");

        assertThat(MetricComparison.of(kind, null, previous))
                .isEqualTo(new MetricComparison(kind, null, previous, null, null));
    }

    @ParameterizedTest
    @EnumSource(MetricKind.class)
    void noValueAndNoPreviousGiveOnlyNulls(MetricKind kind) {
        assertThat(MetricComparison.of(kind, null, null))
                .isEqualTo(new MetricComparison(kind, null, null, null, null));
    }

    @Test
    void previousZeroCountGivesDelta() {
        BigDecimal value = new BigDecimal("3");

        assertThat(MetricComparison.of(MetricKind.ISSUES_TOTAL, value, BigDecimal.ZERO))
                .isEqualTo(new MetricComparison(MetricKind.ISSUES_TOTAL, value, BigDecimal.ZERO, value, Trend.WORSE));
    }

    @Test
    void zeroCountNowAndBeforeIsSame() {
        assertThat(MetricComparison.of(MetricKind.ISSUES_TOTAL, BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualTo(new MetricComparison(
                        MetricKind.ISSUES_TOTAL, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Trend.SAME));
    }

    @Test
    void previousZeroShareIsValueAndNotAbsence() {
        BigDecimal value = new BigDecimal("50.0");
        BigDecimal previous = new BigDecimal("0.0");

        assertThat(MetricComparison.of(MetricKind.CLEAN_GOALS_SHARE, value, previous))
                .isEqualTo(new MetricComparison(MetricKind.CLEAN_GOALS_SHARE, value, previous, value, Trend.BETTER));
    }

    @Test
    void kindsDescribeMetrics() {
        assertThat(MetricKind.values())
                .extracting(MetricKind::key, MetricKind::title, MetricKind::unit, MetricKind::higherIsBetter)
                .containsExactlyInAnyOrder(
                        tuple("clean_goals_share", "Доля корректных целей", "%", true),
                        tuple("issues_total", "Найдено проблем", "шт.", false),
                        tuple("cascade_coverage", "Покрытие каскадом", "%", true));
    }
}
