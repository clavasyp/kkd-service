package ru.kkd.domain.model;

import java.util.Locale;

/** Значения перечислений текстом: в базе и API они в нижнем регистре, в коде в верхнем. */
public final class EnumText {

    private EnumText() {
    }

    public static String format(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Разбор строгий: регистр важен, а неизвестное значение это исключение, а не null. */
    public static <E extends Enum<E>> E parse(Class<E> type, String text) {
        for (E constant : type.getEnumConstants()) {
            if (format(constant).equals(text)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Неизвестное значение " + type.getSimpleName() + ": " + text);
    }
}
