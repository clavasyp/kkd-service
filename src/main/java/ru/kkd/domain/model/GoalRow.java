package ru.kkd.domain.model;

import java.time.LocalDate;

/** Цель на дату снимка. Могут быть null: ownerName, parentGoalId, dueDate. */
public record GoalRow(
        String id,
        String title,
        String unitId,
        GoalLevel level,
        String ownerName,
        String parentGoalId,
        String status,
        LocalDate dueDate) {

    private static final String ACTIVE_STATUS = "active";

    public boolean isActive() {
        return ACTIVE_STATUS.equals(status);
    }
}
