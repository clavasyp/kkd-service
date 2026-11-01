package ru.kkd.domain.rules;

import java.util.Map;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

/** Включённые строки справочника kkd_rule: код правила и его важность. Выключенного правила здесь нет. */
public record RuleCatalog(Map<RuleCode, Severity> severities) {

    // Копия только для чтения: правку исходной коллекции каталог не увидит.
    public RuleCatalog {
        severities = Map.copyOf(severities);
    }

    public boolean isEnabled(RuleCode code) {
        return severities.containsKey(code);
    }

    public Severity severityOf(RuleCode code) {
        return severities.get(code);
    }
}
