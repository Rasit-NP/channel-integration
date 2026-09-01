package com.channel.integration.adapter.a;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 공급사 A 의 응답 형식. <b>이 패키지 밖으로 나가지 않는다.</b>
 *
 * <p>package-private 로 둔 것은 규약이 아니라 컴파일러가 강제하는 경계다. 도메인이나 application
 * 계층에서 실수로 참조하면 컴파일되지 않는다.
 *
 * <p>모르는 필드가 늘어도 깨지지 않도록 무시한다. 공급사가 필드를 추가하는 것은 우리 쪽 장애
 * 사유가 아니다.
 */
final class SupplierAResponses {

    private SupplierAResponses() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HotelsResponse(List<Hotel> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotel(String hotelCode, String hotelName, List<RoomType> roomTypes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomType(String roomTypeCode, String roomTypeName, Integer maxOccupancy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailabilityResponse(List<AvailabilityItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailabilityItem(
            String hotelCode,
            String hotelName,
            String roomTypeCode,
            String roomTypeName,
            Integer maxOccupancy,
            Boolean breakfastIncluded,
            String currency,
            List<DailyRate> dailyRates) {
    }

    /**
     * 날짜별 요금. {@code nightlyRate} 는 세금 별도 금액이고, 그날 고객 결제액은
     * {@code nightlyRate + taxAmount} 다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DailyRate(
            LocalDate date,
            Integer remainingRooms,
            Long nightlyRate,
            Long taxAmount) {
    }
}
