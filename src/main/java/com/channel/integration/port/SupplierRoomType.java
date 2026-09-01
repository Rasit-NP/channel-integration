package com.channel.integration.port;

import java.util.Objects;

/**
 * 공급사 객실 타입. 개별 물리 객실이 아니라 타입이다.
 *
 * <p>객실 타입 코드는 <b>해당 숙소 안에서만 유일</b>하다. 다른 숙소에 같은 코드가 존재할 수
 * 있으므로, 하나를 유일하게 가리키려면 (공급사, 숙소 코드, 객실 타입 코드) 세 값이 필요하다.
 */
public record SupplierRoomType(String roomTypeCode, String roomTypeName, int maxOccupancy) {

    public SupplierRoomType {
        Objects.requireNonNull(roomTypeCode, "roomTypeCode");
        Objects.requireNonNull(roomTypeName, "roomTypeName");
        if (maxOccupancy < 1) {
            throw new IllegalArgumentException("최대 수용 인원은 1 이상이어야 한다: " + maxOccupancy);
        }
    }
}
