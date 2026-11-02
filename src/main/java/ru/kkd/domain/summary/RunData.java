package ru.kkd.domain.summary;

import java.util.List;
import ru.kkd.domain.model.GoalRow;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Run;

/** Прогон с целями его снимка и всеми его проблемами, а не только проблемами ветки. КР сводке не нужны. */
public record RunData(Run run, List<GoalRow> goals, List<Issue> issues) {
}
