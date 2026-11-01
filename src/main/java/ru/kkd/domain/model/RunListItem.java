package ru.kkd.domain.model;

/** Строка списка прогонов: прогон и число его строк в kkd_issue. */
public record RunListItem(Run run, int issuesTotal) {
}
