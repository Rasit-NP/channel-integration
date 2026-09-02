package com.channel.integration.domain;

import java.util.Objects;

/**
 * 공급사 숙소 코드와 내부 숙소 식별자의 매핑.
 *
 * <p>숙소명은 들어 있지 않다. 이름은 재고·요금 응답에 매번 실려 오므로 저장하지 않는다.
 * 저장하는 것은 <b>정체</b>뿐이고, 내용은 조회할 때마다 공급사에서 온다.
 */
public record PropertyMapping(long internalId, SupplierCode supplier, String propertyCode) {

    public PropertyMapping {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(propertyCode, "propertyCode");
    }
}
