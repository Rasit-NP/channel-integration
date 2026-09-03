package com.channel.integration.adapter.a;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.channel.integration.adapter.a.SupplierAResponses.AvailabilityItem;
import com.channel.integration.adapter.a.SupplierAResponses.AvailabilityResponse;
import com.channel.integration.adapter.a.SupplierAResponses.DailyRate;
import com.channel.integration.adapter.a.SupplierAResponses.Hotel;
import com.channel.integration.adapter.a.SupplierAResponses.HotelsResponse;
import com.channel.integration.domain.DailyInventory;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.NightlyRate;
import com.channel.integration.domain.StayPrice;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

/**
 * 공급사 A 응답을 표준 모델로 옮긴다.
 *
 * <p>변환을 어댑터 본체에서 분리한 이유는, 호출(네트워크·타임아웃·실패 판정)과 변환(형식 차이
 * 흡수)이 서로 다른 이유로 바뀌기 때문이다. 변환 규칙만 확인하고 싶을 때 네트워크를 띄울 필요가
 * 없기도 하다.
 *
 * <p>A 는 날짜별 단가를 세금 별도로 주므로 {@link StayPrice#fromNightlyRates} 를 쓴다. 합산
 * 규칙 자체는 도메인이 갖고 있고, 여기서는 어느 팩토리를 쓸지 고르고 요청 기간을 넘긴다.
 * 공급사가 요청하지 않은 날짜를 얹어 보내도 총액이 부풀지 않는 것은 그 팩토리가 보장한다.
 */
final class SupplierAMapper {

    private static final Logger log = LoggerFactory.getLogger(SupplierAMapper.class);

    private SupplierAMapper() {
    }

    static List<SupplierProperty> toProperties(HotelsResponse response) {
        if (response == null || response.items() == null) {
            return List.of();
        }
        List<SupplierProperty> properties = new ArrayList<>();
        for (Hotel hotel : response.items()) {
            if (hotel == null || hotel.hotelCode() == null) {
                continue;
            }
            try {
                properties.add(new SupplierProperty(
                        hotel.hotelCode(),
                        Objects.requireNonNullElse(hotel.hotelName(), hotel.hotelCode()),
                        toRoomTypes(hotel)));
            } catch (IllegalArgumentException e) {
                log.debug("숙소를 옮길 수 없어 건너뛴다: hotel={} 사유={}", hotel.hotelCode(), e.getMessage());
            }
        }
        return List.copyOf(properties);
    }

    private static List<SupplierRoomType> toRoomTypes(Hotel hotel) {
        if (hotel.roomTypes() == null) {
            return List.of();
        }
        List<SupplierRoomType> roomTypes = new ArrayList<>();
        for (SupplierAResponses.RoomType roomType : hotel.roomTypes()) {
            if (roomType == null
                    || roomType.roomTypeCode() == null
                    || roomType.maxOccupancy() == null
                    // 1명도 못 받는 객실 타입은 상품이 아니다. 이 하나 때문에 숙소를 버리지 않는다.
                    || roomType.maxOccupancy() < 1) {
                continue;
            }
            roomTypes.add(new SupplierRoomType(
                    roomType.roomTypeCode(),
                    Objects.requireNonNullElse(roomType.roomTypeName(), roomType.roomTypeCode()),
                    roomType.maxOccupancy()));
        }
        return List.copyOf(roomTypes);
    }

    /**
     * @param dates 요청 기간. 총액이 이 기간에 대해서만 만들어지도록 {@link StayPrice} 에 넘긴다.
     */
    static List<SupplierOffer> toOffers(AvailabilityResponse response, DateRange dates) {
        if (response == null || response.items() == null) {
            return List.of();
        }
        List<SupplierOffer> offers = new ArrayList<>();
        for (AvailabilityItem item : response.items()) {
            try {
                SupplierOffer offer = toOffer(item, dates);
                if (offer != null) {
                    offers.add(offer);
                }
            } catch (IllegalArgumentException e) {
                // 표준 모델로 만들 수 없는 값이 섞여 있다. 이 한 건만 빠지고 나머지는 그대로 간다 —
                // 여기서 안 잡으면 onErrorResume 이 받아 묶음 전체가 실패가 된다.
                log.debug("상품을 옮길 수 없어 건너뛴다: hotel={} roomType={} 사유={}",
                        item == null ? null : item.hotelCode(),
                        item == null ? null : item.roomTypeCode(), e.getMessage());
            }
        }
        return List.copyOf(offers);
    }

    /** 변환할 수 없는 항목은 건너뛴다. 한 건 때문에 나머지 결과를 통째로 버리지 않는다. */
    private static SupplierOffer toOffer(AvailabilityItem item, DateRange dates) {
        if (item == null
                || item.hotelCode() == null
                || item.roomTypeCode() == null
                || item.currency() == null
                || item.maxOccupancy() == null
                || item.dailyRates() == null
                || item.dailyRates().isEmpty()) {
            return null;
        }

        List<NightlyRate> nightlyRates = new ArrayList<>();
        List<DailyInventory> inventories = new ArrayList<>();
        for (DailyRate daily : item.dailyRates()) {
            if (daily == null || daily.date() == null) {
                continue;
            }
            if (daily.nightlyRate() != null && daily.taxAmount() != null) {
                nightlyRates.add(new NightlyRate(
                        daily.date(),
                        Money.of(daily.nightlyRate(), item.currency()),
                        Money.of(daily.taxAmount(), item.currency())));
            }
            // 음수 재고는 그 날짜만 버린다. 빠진 날은 재고 0 으로 판정되므로 보수적인 쪽이다.
            if (daily.remainingRooms() != null && daily.remainingRooms() >= 0) {
                inventories.add(new DailyInventory(daily.date(), daily.remainingRooms()));
            }
        }

        // 요청 기간에 대한 총액을 만들 수 없으면(날짜가 모자라거나 중복이거나 비었으면) 여기서
        // 예외가 난다. 잡는 자리는 toOffers 의 항목 경계 한 곳이다.
        StayPrice price = StayPrice.fromNightlyRates(nightlyRates, dates);

        return new SupplierOffer(
                item.hotelCode(),
                Objects.requireNonNullElse(item.hotelName(), item.hotelCode()),
                item.roomTypeCode(),
                Objects.requireNonNullElse(item.roomTypeName(), item.roomTypeCode()),
                item.maxOccupancy(),
                Boolean.TRUE.equals(item.breakfastIncluded()),
                price,
                inventories);
    }
}
