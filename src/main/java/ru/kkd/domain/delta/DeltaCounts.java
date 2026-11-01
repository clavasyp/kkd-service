package ru.kkd.domain.delta;

/** Счётчики дельты ветки. Все три равны null, если сравнивать не с чем, и это не то же самое, что 0. */
public record DeltaCounts(Integer newCount, Integer persisting, Integer resolved) {
}
