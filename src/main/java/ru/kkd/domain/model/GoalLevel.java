package ru.kkd.domain.model;

/** Порядок констант задаёт иерархию: от верхнего уровня к нижнему. */
public enum GoalLevel {
    DEPARTMENT,
    UNIT,
    EMPLOYEE;

    public boolean isHigherThan(GoalLevel other) {
        return ordinal() < other.ordinal();
    }
}
