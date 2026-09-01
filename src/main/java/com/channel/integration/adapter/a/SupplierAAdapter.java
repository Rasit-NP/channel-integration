package com.channel.integration.adapter.a;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.adapter.a.SupplierAResponses.AvailabilityResponse;
import com.channel.integration.adapter.a.SupplierAResponses.HotelsResponse;
import com.channel.integration.adapter.support.HttpFailures;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;

import reactor.core.publisher.Mono;

/**
 * 공급사 A 연동.
 *
 * <p>A 는 <b>실패를 HTTP 상태 코드로 알린다.</b> 그래서 4xx/5xx 를 그대로 실패로 판정한다.
 * 이 판정은 어댑터 안에만 있고, 바깥에는 정규화된 사유만 나간다.
 *
 * <p>예외를 밖으로 던지지 않는다. 어떤 경우에도 {@link SupplierFetchResult} 를 돌려준다.
 * 부분 실패를 허용하려면 실패가 값이어야 하기 때문이다.
 */
class SupplierAAdapter implements SupplierAdapter {

    static final SupplierCode SUPPLIER_CODE = SupplierCode.of("A");

    private static final Logger log = LoggerFactory.getLogger(SupplierAAdapter.class);

    private final WebClient webClient;
    private final SupplierAProperties properties;

    SupplierAAdapter(WebClient webClient, SupplierAProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public SupplierCode supplier() {
        return SUPPLIER_CODE;
    }

    @Override
    public int maxBatchSize() {
        return properties.maxBatchSize();
    }

    @Override
    public Mono<SupplierFetchResult<List<SupplierProperty>>> fetchProperties() {
        return webClient.get()
                .uri("/a/v1/hotels")
                .retrieve()
                .bodyToMono(HotelsResponse.class)
                .map(body -> SupplierFetchResult.success(SupplierAMapper.toProperties(body)))
                .timeout(properties.responseTimeout())
                .onErrorResume(error -> Mono.just(toFailure("숙소 목록", error)));
    }

    @Override
    public Mono<SupplierFetchResult<List<SupplierOffer>>> fetchOffers(
            List<String> propertyCodes, SearchCriteria criteria) {

        if (propertyCodes == null || propertyCodes.isEmpty()) {
            // 물어볼 숙소가 없으면 호출하지 않는다. 빈 조회는 실패가 아니다.
            return Mono.just(SupplierFetchResult.success(List.of()));
        }
        if (propertyCodes.size() > maxBatchSize()) {
            // 묶음을 나누는 건 호출하는 쪽 책임이다. 넘겨서 공급사 오류를 받기 전에 여기서 막는다.
            throw new IllegalArgumentException(
                    "묶음 크기 초과: %d > %d".formatted(propertyCodes.size(), maxBatchSize()));
        }

        return webClient.get()
                .uri(uri -> uri.path("/a/v1/availability")
                        .queryParam("hotelCodes", String.join(",", propertyCodes))
                        .queryParam("checkIn", criteria.dates().checkIn())
                        .queryParam("checkOut", criteria.dates().checkOut())
                        .queryParam("adults", criteria.adults())
                        .queryParam("children", criteria.children())
                        .build())
                .retrieve()
                .bodyToMono(AvailabilityResponse.class)
                .map(body -> SupplierFetchResult.success(SupplierAMapper.toOffers(body)))
                .timeout(properties.responseTimeout())
                .onErrorResume(error -> Mono.just(toFailure("재고·요금", error)));
    }

    private <T> SupplierFetchResult<T> toFailure(String operation, Throwable error) {
        var reason = HttpFailures.fromThrowable(error);
        var detail = HttpFailures.describe(error);
        log.warn("공급사 {} {} 조회 실패: reason={} detail={}", SUPPLIER_CODE, operation, reason, detail);
        return SupplierFetchResult.failure(reason, detail);
    }
}
