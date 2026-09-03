package com.channel.integration.adapter.support;

import java.time.Duration;

/**
 * 공급사 연동 설정. 공급사마다 값이 다르므로 어댑터별로 하나씩 갖는다.
 *
 * <p>타임아웃을 설정으로 뺀 이유는 관측 후 조정하기 위해서다. 지금 값은 근거 있는 초기값이지
 * 측정된 값이 아니다. 실제 응답 시간 분포를 보면 코드 수정 없이 바꿀 수 있어야 한다.
 */
public interface SupplierHttpProperties {

    String baseUrl();

    String apiKey();

    /** 연결 수립 제한. 정상적인 핸드셰이크는 수십 ms 안에 끝난다. */
    Duration connectTimeout();

    /** 요청부터 응답 완료까지의 제한. 공급사 호출은 병렬이므로 가장 느린 곳이 전체를 결정한다. */
    Duration responseTimeout();

    /** 한 번에 조회할 수 있는 숙소 코드 수의 상한. */
    int maxBatchSize();

    /**
     * 묶음 크기 상한이 쓸 수 있는 값인지 확인한다. 각 공급사 설정이 생성 시점에 부른다.
     *
     * <p>0 이나 음수면 <b>검색이 묶음을 나누다 끝나지 않는다.</b> 설정값 하나 때문에 요청
     * 스레드가 멈추는 것이라, 늦게 알수록 원인을 찾기 어렵다. 기동 시점에 못 뜨게 막는 편이
     * 낫다. 무엇이 유효한 값인지는 {@code maxBatchSize()} 를 선언한 여기가 정한다 — 공급사가
     * 늘어도 각자 다시 정하지 않는다.
     */
    static int requireUsableBatchSize(int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("묶음 크기 상한은 1 이상이어야 한다: " + maxBatchSize);
        }
        return maxBatchSize;
    }
}
