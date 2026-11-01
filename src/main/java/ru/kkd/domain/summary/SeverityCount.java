package ru.kkd.domain.summary;

import ru.kkd.domain.model.Severity;

/**
 * Строка разбивки по важности. previous и delta равны null, если сравнивать не с чем.
 * previous равен 0, если база есть, а проблем этой важности не было.
 */
public record SeverityCount(Severity severity, int current, Integer previous, Integer delta) {
}
