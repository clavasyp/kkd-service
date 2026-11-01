package ru.kkd.domain.summary;

import java.util.List;
import ru.kkd.domain.model.Issue;
import ru.kkd.domain.model.Run;
import ru.kkd.domain.model.Snapshot;

/** Прогон с его снимком и всеми его проблемами, а не только проблемами ветки. */
public record RunData(Run run, Snapshot snapshot, List<Issue> issues) {
}
