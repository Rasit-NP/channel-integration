package com.channel.integration.domain;

import java.util.Objects;

/**
 * 공급사 식별자.
 *
 * <p>enum 이 아니라 값 타입인 이유는, 공급사를 추가할 때 도메인 코드를 고치지 않기 위해서다.
 * 각 어댑터가 자기 코드를 선언하고, 도메인은 그것을 값으로만 다룬다.
 */
public record SupplierCode(String value) {

    public SupplierCode {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("공급사 코드는 비어 있을 수 없다");
        }
    }

    public static SupplierCode of(String value) {
        return new SupplierCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
