package ru.kkd.domain.model;

/** Подразделение. У корня parentId равен null. */
public record Unit(String id, String name, String parentId) {
}
