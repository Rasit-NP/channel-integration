package com.channel.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvailabilityTest {

    private static final DateRange THREE_NIGHTS =
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

    private static DailyInventory inventory(String date, int remaining) {
        return new DailyInventory(LocalDate.parse(date), remaining);
    }

    @Test
    @DisplayName("날짜별 잔여 수의 최솟값이 예약 가능 객실 수다")
    void takesMinimum() {
        Availability availability = Availability.forStay(List.of(
                inventory("2026-09-01", 3),
                inventory("2026-09-02", 1),
                inventory("2026-09-03", 5)), THREE_NIGHTS);

        assertThat(availability.availableRooms()).isEqualTo(1);
        assertThat(availability.bookable()).isTrue();
    }

    @Test
    @DisplayName("중간 하루가 0 이면 연박이 불가능하다 — 합계나 평균으로 판정하면 안 된다")
    void bottleneckDayDecides() {
        Availability availability = Availability.forStay(List.of(
                inventory("2026-09-01", 2),
                inventory("2026-09-02", 0),
                inventory("2026-09-03", 4)), THREE_NIGHTS);

        // 합계는 6, 평균은 2 지만 실제로는 예약할 수 없다.
        assertThat(availability.availableRooms()).isZero();
        assertThat(availability.bookable()).isFalse();
    }

    @Test
    @DisplayName("요청 기간의 날짜가 빠져 있으면 그 날은 재고 0 으로 본다")
    void missingDateCountsAsZero() {
        Availability availability = Availability.forStay(List.of(
                inventory("2026-09-01", 3),
                // 09-02 누락
                inventory("2026-09-03", 5)), THREE_NIGHTS);

        assertThat(availability.availableRooms()).isZero();
    }

    @Test
    @DisplayName("재고가 아예 없으면 0 이다")
    void emptyInventoryIsZero() {
        assertThat(Availability.forStay(List.of(), THREE_NIGHTS).availableRooms()).isZero();
        assertThat(Availability.forStay(null, THREE_NIGHTS).availableRooms()).isZero();
    }

    @Test
    @DisplayName("요청 기간 밖의 날짜는 판정에 쓰지 않는다")
    void ignoresDatesOutsideRange() {
        Availability availability = Availability.forStay(List.of(
                inventory("2026-08-31", 0),   // 체크인 전날
                inventory("2026-09-01", 3),
                inventory("2026-09-02", 2),
                inventory("2026-09-03", 5),
                inventory("2026-09-04", 0)), // 체크아웃일 — 숙박일 아님
                THREE_NIGHTS);

        assertThat(availability.availableRooms()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 날짜가 중복으로 오면 보수적으로 작은 값을 택한다")
    void duplicateDateTakesSmaller() {
        Availability availability = Availability.forStay(List.of(
                inventory("2026-09-01", 3),
                inventory("2026-09-01", 1),
                inventory("2026-09-02", 4),
                inventory("2026-09-03", 5)), THREE_NIGHTS);

        assertThat(availability.availableRooms()).isEqualTo(1);
    }
}
