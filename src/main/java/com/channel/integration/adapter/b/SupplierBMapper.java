package com.channel.integration.adapter.b;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.channel.integration.adapter.b.SupplierBResponses.Inventory;
import com.channel.integration.adapter.b.SupplierBResponses.PropertiesResponse;
import com.channel.integration.adapter.b.SupplierBResponses.Property;
import com.channel.integration.adapter.b.SupplierBResponses.Room;
import com.channel.integration.adapter.b.SupplierBResponses.SearchItem;
import com.channel.integration.adapter.b.SupplierBResponses.SearchResponse;
import com.channel.integration.domain.DailyInventory;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.StayPrice;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

/**
 * 공급사 B 응답을 표준 모델로 옮긴다.
 *
 * <p>B 는 <b>숙박 기간 총액만</b> 주므로 {@link StayPrice#fromTotal} 을 쓴다. 총액을 날짜 수로
 * 나눠 날짜별 요금을 만들지 않는다 — 없는 정보를 지어내는 일이고, 그 값이 고객에게 노출되면
 * 실제 결제액과 어긋난다.
 *
 * <p><b>A 와 달리 요청 기간을 받지 않는다.</b> A 는 날짜별 요금을 주기 때문에 어느 날짜를 합칠지
 * 정해야 하지만, B 는 총액 하나뿐이라 기간에 맞춰 자를 대상이 없다. 뒤집어 말하면 <b>그 총액이
 * 정말 요청한 기간에 대한 값인지 확인할 방법이 없다.</b> B 를 믿는 수밖에 없는 지점이고, 이건
 * 표준 모델이 잃는 것 중 하나다.
 */
final class SupplierBMapper {

    private static final Logger log = LoggerFactory.getLogger(SupplierBMapper.class);

    private SupplierBMapper() {
    }

    static List<SupplierProperty> toProperties(PropertiesResponse response) {
        if (response == null || response.data() == null || response.data().items() == null) {
            return List.of();
        }
        List<SupplierProperty> properties = new ArrayList<>();
        for (Property property : response.data().items()) {
            if (property == null || property.propertyId() == null) {
                continue;
            }
            properties.add(new SupplierProperty(
                    property.propertyId(),
                    Objects.requireNonNullElse(property.propertyName(), property.propertyId()),
                    toRoomTypes(property)));
        }
        return List.copyOf(properties);
    }

    private static List<SupplierRoomType> toRoomTypes(Property property) {
        if (property.rooms() == null) {
            return List.of();
        }
        List<SupplierRoomType> roomTypes = new ArrayList<>();
        for (Room room : property.rooms()) {
            if (room == null || room.roomId() == null || room.maxOccupancy() == null) {
                continue;
            }
            roomTypes.add(new SupplierRoomType(
                    room.roomId(),
                    Objects.requireNonNullElse(room.roomName(), room.roomId()),
                    room.maxOccupancy()));
        }
        return List.copyOf(roomTypes);
    }

    static List<SupplierOffer> toOffers(SearchResponse response) {
        if (response == null || response.data() == null || response.data().items() == null) {
            return List.of();
        }
        List<SupplierOffer> offers = new ArrayList<>();
        for (SearchItem item : response.data().items()) {
            SupplierOffer offer = toOffer(item);
            if (offer != null) {
                offers.add(offer);
            }
        }
        return List.copyOf(offers);
    }

    /** 변환할 수 없는 항목은 건너뛴다. 한 건 때문에 나머지 결과를 통째로 버리지 않는다. */
    private static SupplierOffer toOffer(SearchItem item) {
        if (item == null
                || item.propertyId() == null
                || item.roomId() == null
                || item.currency() == null
                || item.maxOccupancy() == null
                || item.totalPrice() == null) {
            return null;
        }

        if (!Boolean.TRUE.equals(item.taxIncluded())) {
            // 우리 표준은 세금 포함 총액인데, B 는 세액을 따로 주지 않는다. 세금 별도 총액을
            // 받으면 고객이 실제로 낼 금액을 만들 수 없고, 세액을 추정해 더하는 것은 지어내는
            // 일이다. 그 상품은 성립하지 않는 것으로 본다.
            log.debug("세금 포함 총액이 아니어서 건너뛴다: property={} room={}",
                    item.propertyId(), item.roomId());
            return null;
        }

        StayPrice price;
        try {
            price = StayPrice.fromTotal(Money.of(item.totalPrice(), item.currency()));
        } catch (IllegalArgumentException e) {
            log.debug("표준 요금으로 옮길 수 없어 건너뛴다: property={} room={} 사유={}",
                    item.propertyId(), item.roomId(), e.getMessage());
            return null;
        }

        return new SupplierOffer(
                item.propertyId(),
                Objects.requireNonNullElse(item.propertyName(), item.propertyId()),
                item.roomId(),
                Objects.requireNonNullElse(item.roomName(), item.roomId()),
                item.maxOccupancy(),
                Boolean.TRUE.equals(item.breakfastIncluded()),
                price,
                toInventories(item));
    }

    /** 날짜가 빠진 재고는 버린다. 판정에서 그 날은 재고 0 으로 취급된다. */
    private static List<DailyInventory> toInventories(SearchItem item) {
        if (item.inventory() == null) {
            return List.of();
        }
        List<DailyInventory> inventories = new ArrayList<>();
        for (Inventory inventory : item.inventory()) {
            if (inventory == null || inventory.date() == null || inventory.remainingRooms() == null) {
                continue;
            }
            inventories.add(new DailyInventory(inventory.date(), inventory.remainingRooms()));
        }
        return List.copyOf(inventories);
    }
}
