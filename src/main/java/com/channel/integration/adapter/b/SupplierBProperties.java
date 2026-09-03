package com.channel.integration.adapter.b;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.channel.integration.adapter.support.SupplierHttpProperties;

/**
 * 공급사 B 연동 설정.
 *
 * <p>타임아웃 근거는 A 와 같다(README §타임아웃). 묶음 크기 상한도 A 와 같은 50 이다 — 두 공급사가
 * 같은 값을 쓸 뿐이고, 값 자체는 어댑터마다 따로 선언한다. 검색 로직은 어댑터가 선언한 값에 맞춰
 * 나눌 뿐 그 값이 무엇인지 알지 못한다.
 */
@ConfigurationProperties(prefix = "supplier.b")
record SupplierBProperties(
        @DefaultValue("http://localhost:9090") String baseUrl,
        @DefaultValue("local-dev-key") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration responseTimeout,
        @DefaultValue("50") int maxBatchSize) implements SupplierHttpProperties {

    SupplierBProperties {
        maxBatchSize = SupplierHttpProperties.requireUsableBatchSize(maxBatchSize);
    }
}
