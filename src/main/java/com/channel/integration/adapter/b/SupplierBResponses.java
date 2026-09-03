package com.channel.integration.adapter.b;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 공급사 B 의 응답 형식. <b>이 패키지 밖으로 나가지 않는다.</b>
 *
 * <p>package-private 로 둔 것은 규약이 아니라 컴파일러가 강제하는 경계다. A 와 같은 방식이다.
 *
 * <p>B 는 모든 응답을 {@code resultCode / resultMessage / data} 봉투에 담아 준다. <b>성공이든
 * 실패든 HTTP 200 이고</b>, 실패는 봉투의 코드로만 알린다. 그래서 두 응답 타입이 {@link Envelope}
 * 를 함께 구현하게 해, 코드 확인을 한 자리에서 하도록 했다.
 */
final class SupplierBResponses {

    private SupplierBResponses() {
    }

    /** 결과 코드를 들고 있는 응답. 성공 여부 판정이 이 코드 하나에 걸린다. */
    interface Envelope {
        String resultCode();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PropertiesResponse(String resultCode, String resultMessage, PropertiesData data)
            implements Envelope {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PropertiesData(List<Property> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Property(String propertyId, String propertyName, List<Room> rooms) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Room(String roomId, String roomName, Integer maxOccupancy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(String resultCode, String resultMessage, SearchData data)
            implements Envelope {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchData(List<SearchItem> items) {
    }

    /**
     * B 는 <b>숙박 기간 총액만</b> 준다. 날짜별 단가도 세액도 없다. {@code taxIncluded} 는 그
     * 총액이 세금을 포함한 값인지를 알려준다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchItem(
            String propertyId,
            String propertyName,
            String roomId,
            String roomName,
            Integer maxOccupancy,
            Boolean breakfastIncluded,
            String currency,
            Long totalPrice,
            Boolean taxIncluded,
            List<Inventory> inventory) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inventory(LocalDate date, Integer remainingRooms) {
    }
}
