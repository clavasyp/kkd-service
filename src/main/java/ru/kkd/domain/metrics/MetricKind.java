package ru.kkd.domain.metrics;

public enum MetricKind {
    CLEAN_GOALS_SHARE("clean_goals_share", "Доля корректных целей", "%", true),
    ISSUES_TOTAL("issues_total", "Найдено проблем", "шт.", false),
    CASCADE_COVERAGE("cascade_coverage", "Покрытие каскадом", "%", true);

    private final String key;
    private final String title;
    private final String unit;
    private final boolean higherIsBetter;

    MetricKind(String key, String title, String unit, boolean higherIsBetter) {
        this.key = key;
        this.title = title;
        this.unit = unit;
        this.higherIsBetter = higherIsBetter;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public boolean higherIsBetter() {
        return higherIsBetter;
    }
}
