package ru.kkd.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.metrics.MetricComparison;
import ru.kkd.domain.model.EnumText;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.Unit;
import ru.kkd.domain.summary.SeverityCount;
import ru.kkd.domain.summary.Summary;

/** Ответ GET /api/kkd/summary: раздел 8.3 design.md. Порядок полей в JSON задан порядком компонентов записи. */
public record SummaryResponse(
        RunRef run,
        RunRef comparedTo,
        UnitRef unit,
        List<Metric> metrics,
        List<SeverityRow> issuesBySeverity,
        Delta delta) {

    public static SummaryResponse from(Summary summary) {
        return new SummaryResponse(
                RunRef.from(summary.run()),
                summary.comparedTo() == null ? null : RunRef.from(summary.comparedTo()),
                UnitRef.from(summary.unit()),
                summary.metrics().stream().map(Metric::from).toList(),
                summary.issuesBySeverity().stream().map(SeverityRow::from).toList(),
                Delta.from(summary.delta()));
    }

    public record RunRef(String id, LocalDate snapshotDate) {

        public static RunRef from(Run run) {
            return new RunRef(run.id(), run.snapshotDate());
        }
    }

    public record UnitRef(String id, String name) {

        public static UnitRef from(Unit unit) {
            return new UnitRef(unit.id(), unit.name());
        }
    }

    /** BigDecimal пишется как есть: доля 80.0 остаётся 80.0, счётчик остаётся целым. */
    public record Metric(
            String key,
            String title,
            String unit,
            BigDecimal value,
            BigDecimal previous,
            BigDecimal delta,
            String trend,
            boolean higherIsBetter) {

        public static Metric from(MetricComparison metric) {
            return new Metric(
                    metric.kind().key(),
                    metric.kind().title(),
                    metric.kind().unit(),
                    metric.value(),
                    metric.previous(),
                    metric.delta(),
                    metric.trend() == null ? null : EnumText.format(metric.trend()),
                    metric.kind().higherIsBetter());
        }
    }

    public record SeverityRow(String code, int current, Integer previous, Integer delta) {

        public static SeverityRow from(SeverityCount count) {
            return new SeverityRow(
                    EnumText.format(count.severity()), count.current(), count.previous(), count.delta());
        }
    }

    public record Delta(@JsonProperty("new") Integer newCount, Integer persisting, Integer resolved) {

        public static Delta from(DeltaCounts counts) {
            return new Delta(counts.newCount(), counts.persisting(), counts.resolved());
        }
    }
}
