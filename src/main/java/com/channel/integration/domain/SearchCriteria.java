package com.channel.integration.domain;

import java.util.Objects;

/**
 * 고객 검색 조건. 날짜와 인원뿐이다.
 *
 * <p>지역·키워드 필터는 공급사가 지역 정보를 주지 않으므로 다루지 않는다.
 */
public record SearchCriteria(DateRange dates, int adults, int children) {

    public SearchCriteria {
        Objects.requireNonNull(dates, "dates");
        if (adults < 1) {
            throw new IllegalArgumentException("성인은 1명 이상이어야 한다: " + adults);
        }
        if (children < 0) {
            throw new IllegalArgumentException("아동 수는 음수일 수 없다: " + children);
        }
    }

    /** {@code maxOccupancy} 는 성인+아동 합산 기준이다. */
    public int totalGuests() {
        return adults + children;
    }
}
