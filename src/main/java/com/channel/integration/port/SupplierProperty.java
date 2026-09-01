package com.channel.integration.port;

import java.util.List;
import java.util.Objects;

/**
 * 공급사 숙소 목록의 한 항목. 매핑을 만들 때 쓴다.
 *
 * <p>요금·재고는 들어 있지 않다. 숙소 목록은 정적 콘텐츠이고, 요금·재고는 조회할 때마다
 * 달라지는 별개의 API 다.
 *
 * <p>여기 담긴 코드는 <b>공급사가 쓰는 코드</b>다. 내부 식별자로 바꾸는 것은 매핑을 가진
 * application 계층의 일이다. 어댑터는 표현 형식만 통일하고, 식별자 해석에는 관여하지 않는다.
 */
public record SupplierProperty(
        String propertyCode,
        String propertyName,
        List<SupplierRoomType> roomTypes) {

    public SupplierProperty {
        Objects.requireNonNull(propertyCode, "propertyCode");
        Objects.requireNonNull(propertyName, "propertyName");
        roomTypes = roomTypes == null ? List.of() : List.copyOf(roomTypes);
    }
}
