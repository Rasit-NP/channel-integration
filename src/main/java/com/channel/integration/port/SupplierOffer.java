package com.channel.integration.port;

import java.util.List;
import java.util.Objects;

import com.channel.integration.domain.DailyInventory;
import com.channel.integration.domain.StayPrice;

/**
 * 공급사 재고·요금 조회 결과 한 건. 객실 타입 단위다.
 *
 * <p>요금은 이미 표준 형태({@link StayPrice} — 세금 포함 총액)로 정규화되어 있다. 공급사가 날짜별
 * 단가를 주든 기간 총액을 주든, 이 타입까지 오면 형태가 같다. <b>표현의 차이를 흡수하는 것이
 * 어댑터의 책임</b>이다.
 *
 * <p>다만 식별자는 아직 공급사 코드 그대로다. 내부 식별자로 바꾸려면 DB 의 매핑이 필요하고,
 * 그건 application 계층의 일이다. 어댑터는 <b>형태</b>를 통일하고, application 은 <b>정체</b>를
 * 확정한다.
 *
 * <p>예약 가능 객실 수도 여기서 계산하지 않는다. 요청 기간을 알아야 판정할 수 있고, 판정 규칙은
 * 공급사가 아니라 우리 정책이기 때문이다.
 */
public record SupplierOffer(
        String propertyCode,
        String propertyName,
        String roomTypeCode,
        String roomTypeName,
        int maxOccupancy,
        boolean breakfastIncluded,
        StayPrice price,
        List<DailyInventory> inventories) {

    public SupplierOffer {
        Objects.requireNonNull(propertyCode, "propertyCode");
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(roomTypeCode, "roomTypeCode");
        Objects.requireNonNull(roomTypeName, "roomTypeName");
        Objects.requireNonNull(price, "price");
        inventories = inventories == null ? List.of() : List.copyOf(inventories);
    }
}
