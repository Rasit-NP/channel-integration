package com.channel.integration.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 검색 조립 설정.
 *
 * @param maxConcurrency 한 공급사에 동시에 띄울 묶음 호출 수의 상한. 상한 없이 병렬로 띄우면
 *                       숙소가 늘어날수록 우리가 공급사를 두드리는 꼴이 되고 호출 한도에 걸린다.
 * @param timeout        검색 전체의 상한. 어댑터가 묶음마다 응답 제한을 걸지만 묶음이 늘면 합이
 *                       길어지므로, 전체에도 상한을 둔다. 초과하면 <b>그때까지 온 결과로 응답</b>하고
 *                       못 받은 공급사는 실패로 표시한다. 아무것도 못 주는 것보다 낫다.
 */
@ConfigurationProperties(prefix = "supplier.search")
public record SearchProperties(
        @DefaultValue("4") int maxConcurrency,
        @DefaultValue("5s") Duration timeout) {

    public SearchProperties {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("동시 호출 상한은 1 이상이어야 한다: " + maxConcurrency);
        }
    }
}
