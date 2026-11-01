package ru.kkd.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

// Раздел 10 design.md: нижний регистр, как в базе и API, разбор строгий.
class EnumTextTest {

    @ParameterizedTest
    @CsvSource({"DEPARTMENT, department", "UNIT, unit", "EMPLOYEE, employee"})
    void formatGivesLowerCase(GoalLevel level, String expected) {
        assertThat(EnumText.format(level)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(GoalLevel.class)
    void parseReturnsConstantForItsText(GoalLevel level) {
        assertThat(EnumText.parse(GoalLevel.class, EnumText.format(level))).isSameAs(level);
    }

    // В колонке level снимка нет CHECK: другой регистр и неизвестное значение доходят до разбора и дают failed.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"DEPARTMENT", "Department", "weird", "", " unit"})
    void parseRejectsAnyOtherTextWithException(String text) {
        assertThatThrownBy(() -> EnumText.parse(GoalLevel.class, text))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
