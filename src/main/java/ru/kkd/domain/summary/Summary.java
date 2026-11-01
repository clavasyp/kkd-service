package ru.kkd.domain.summary;

import java.util.List;
import ru.kkd.domain.delta.DeltaCounts;
import ru.kkd.domain.metrics.MetricComparison;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.Unit;

/** Данные сводки: раздел 8.3 design.md. comparedTo равен null, если сравнивать не с чем. */
public record Summary(
        Run run,
        Run comparedTo,
        Unit unit,
        List<MetricComparison> metrics,
        List<SeverityCount> issuesBySeverity,
        DeltaCounts delta) {
}
