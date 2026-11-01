package ru.kkd.domain.metrics;

import java.math.BigDecimal;

/** Строка метрики в сводке. Могут быть null: value, previous, delta, trend. */
public record MetricComparison(
        MetricKind kind,
        BigDecimal value,
        BigDecimal previous,
        BigDecimal delta,
        Trend trend) {

    /** value и previous уже округлены в MetricCalculator, поэтому дельта сходится с видимыми числами. */
    public static MetricComparison of(MetricKind kind, BigDecimal value, BigDecimal previous) {
        if (value == null || previous == null) {
            return new MetricComparison(kind, value, previous, null, null);
        }
        BigDecimal delta = value.subtract(previous);
        return new MetricComparison(kind, value, previous, delta, trendOf(kind, delta));
    }

    private static Trend trendOf(MetricKind kind, BigDecimal delta) {
        int sign = delta.compareTo(BigDecimal.ZERO);
        if (sign == 0) {
            return Trend.SAME;
        }
        // Знак дельты сам по себе ничего не говорит: рост числа проблем это ухудшение.
        boolean grew = sign > 0;
        return grew == kind.higherIsBetter() ? Trend.BETTER : Trend.WORSE;
    }
}
