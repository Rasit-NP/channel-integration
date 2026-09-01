package com.channel.integration.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 숙박 기간.
 *
 * <p><b>체크아웃일은 숙박일에 포함되지 않는다.</b> 09-01 체크인 / 09-04 체크아웃이면 숙박일은
 * 09-01, 09-02, 09-03 세 날이다. 이 규칙을 여기 한 곳에만 두어, 날짜를 훑는 코드가 각자
 * 경계를 계산하다 어긋나는 일을 막는다.
 */
public record DateRange(LocalDate checkIn, LocalDate checkOut) {

    public DateRange {
        Objects.requireNonNull(checkIn, "checkIn");
        Objects.requireNonNull(checkOut, "checkOut");
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException(
                    "체크아웃은 체크인보다 뒤여야 한다: %s ~ %s".formatted(checkIn, checkOut));
        }
    }

    public static DateRange of(LocalDate checkIn, LocalDate checkOut) {
        return new DateRange(checkIn, checkOut);
    }

    /** 숙박 일수. */
    public int nights() {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** 체크인일부터 체크아웃 전날까지. 재고·요금을 확인해야 하는 날짜들이다. */
    public List<LocalDate> stayDates() {
        return checkIn.datesUntil(checkOut).toList();
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(checkIn) && date.isBefore(checkOut);
    }
}
