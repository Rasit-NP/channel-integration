package com.channel.integration.domain;

import java.util.Objects;

/**
 * 공급사 객실 타입 코드와 내부 객실 타입 식별자의 매핑.
 *
 * <p>객실 타입 코드는 <b>해당 숙소 안에서만 유일</b>하므로 숙소 코드가 함께 있어야 하나를
 * 유일하게 가리킬 수 있다. {@link PropertyMapping} 의 내부 식별자를 참조하지 않고 공급사
 * 숙소 코드를 그대로 들고 있는 것은, 공급사 응답을 정규화할 때 세 값으로 바로 찾기 위해서다.
 */
public record RoomTypeMapping(
        long internalId, SupplierCode supplier, String propertyCode, String roomTypeCode) {

    public RoomTypeMapping {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(propertyCode, "propertyCode");
        Objects.requireNonNull(roomTypeCode, "roomTypeCode");
    }
}
