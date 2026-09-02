package com.channel.integration.domain;

import java.util.Objects;

/**
 * 고객에게 보이는 검색 결과 한 건. 객실 타입 단위다.
 *
 * <p>공급사 코드는 여기 없다. 이 타입까지 오면 <b>정체가 내부 식별자로 확정</b>되어 있고,
 * 요금도 표준 형태({@link StayPrice} — 세금 포함 총액)로 통일되어 있다. 어느 공급사에서 왔는지는
 * {@code supplier} 로만 남는다 — 출처는 밝히되 형태는 같아야 하기 때문이다.
 *
 * <p>{@code breakfastIncluded} 를 요금과 같은 층위에 두는 이유는, 조건이 다른 상품을 값만으로
 * 비교하면 고객이 다른 것을 비교하게 되기 때문이다. 싸다는 사실보다 <b>무엇이 포함된 값인지</b>가
 * 함께 보여야 비교가 성립한다.
 */
public record Stay(
        long propertyId,
        String propertyName,
        long roomTypeId,
        String roomTypeName,
        int maxOccupancy,
        Availability availability,
        SupplierCode supplier,
        boolean breakfastIncluded,
        StayPrice price) {

    public Stay {
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(roomTypeName, "roomTypeName");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(price, "price");
    }

    /** 요청 기간 전체를 예약할 수 있는 객실 수. 0 인 상품은 애초에 결과에 담기지 않는다. */
    public int availableRooms() {
        return availability.availableRooms();
    }
}
