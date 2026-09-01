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
}
