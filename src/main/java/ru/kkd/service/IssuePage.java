package ru.kkd.service;

import java.util.List;
import ru.kkd.domain.model.Issue;

/** Страница проблем прогона. total не зависит от limit и offset. */
public record IssuePage(String runId, List<Item> items, int total, int limit, int offset) {

    /** Проблема и название её цели из снимка этого прогона. */
    public record Item(Issue issue, String goalTitle) {
    }
}
