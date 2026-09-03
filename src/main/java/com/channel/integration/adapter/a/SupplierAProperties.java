package com.channel.integration.adapter.a;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.channel.integration.adapter.support.SupplierHttpProperties;

/**
 * 공급사 A 연동 설정.
 *
 * <p>타임아웃 기본값은 근거 있는 초기값이지 측정된 값이 아니다. 연결은 정상이면 수십 ms 안에
 * 끝나므로 2초를 넘기면 경로 이상으로 본다. 응답은 병렬 호출에서 가장 느린 공급사가 전체를
 * 결정하므로, 검색 응답 목표를 5초 안쪽에 두고 정규화·병합 여유를 남겨 3초로 잡았다.
 */
@ConfigurationProperties(prefix = "supplier.a")
record SupplierAProperties(
        @DefaultValue("http://localhost:9090") String baseUrl,
        @DefaultValue("local-dev-key") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration responseTimeout,
        @DefaultValue("50") int maxBatchSize) implements SupplierHttpProperties {

    SupplierAProperties {
        maxBatchSize = SupplierHttpProperties.requireUsableBatchSize(maxBatchSize);
    }
}
