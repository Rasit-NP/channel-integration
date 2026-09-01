package com.channel.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    @Test
    @DisplayName("체크아웃일은 숙박일에 포함되지 않는다 — 09-01 ~ 09-04 는 3박")
    void checkOutIsExclusive() {
        DateRange range = DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

        assertThat(range.nights()).isEqualTo(3);
        assertThat(range.stayDates()).containsExactly(
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"),
                LocalDate.parse("2026-09-03"));
    }

    @Test
    @DisplayName("체크아웃일 자체는 기간에 포함되지 않는다")
    void coversExcludesCheckOut() {
        DateRange range = DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

        assertThat(range.covers(LocalDate.parse("2026-09-01"))).isTrue();
        assertThat(range.covers(LocalDate.parse("2026-09-03"))).isTrue();
        assertThat(range.covers(LocalDate.parse("2026-09-04"))).isFalse();
        assertThat(range.covers(LocalDate.parse("2026-08-31"))).isFalse();
    }

    @Test
    @DisplayName("체크아웃이 체크인보다 앞서거나 같으면 만들 수 없다")
    void rejectsInvalidRange() {
        LocalDate day = LocalDate.parse("2026-09-01");

        assertThatThrownBy(() -> DateRange.of(day, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DateRange.of(day, day.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
