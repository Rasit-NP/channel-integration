package com.channel.integration.adapter.b;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.adapter.b.SupplierBResponses.Envelope;
import com.channel.integration.adapter.b.SupplierBResponses.PropertiesResponse;
import com.channel.integration.adapter.b.SupplierBResponses.SearchResponse;
import com.channel.integration.adapter.support.HttpFailures;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;

import reactor.core.publisher.Mono;

/**
 * 공급사 B 연동.
 *
 * <p>B 는 <b>장애 상황에서도 HTTP 200 을 주고</b>, 실패를 응답 본문의 결과 코드로만 알린다.
 * 상태 코드만 보면 장애를 정상 응답으로 처리하게 되므로, 본문 코드 확인을
 * {@link SupplierBResults} 가 맡는다.
 *
 * <p><b>그렇다고 상태 코드 판정을 버리지는 않는다.</b> 연결이 안 되거나 응답이 늦거나 인프라가
 * 5xx 를 내는 것은 어느 공급사에게나 똑같이 생기는 일이고, 그건 A 와 같은
 * {@link HttpFailures} 가 처리한다. <b>두 층이 다 필요하다</b> — 전송 계층의 실패와 공급사가
 * 알려주는 실패는 다른 사건이다.
 *
 * <p>예외를 밖으로 던지지 않는다. 어떤 경우에도 {@link SupplierFetchResult} 를 돌려준다.
 */
class SupplierBAdapter implements SupplierAdapter {

    static final SupplierCode SUPPLIER_CODE = SupplierCode.of("B");

    private static final Logger log = LoggerFactory.getLogger(SupplierBAdapter.class);

    private final WebClient webClient;
    private final SupplierBProperties properties;

    SupplierBAdapter(WebClient webClient, SupplierBProperties properties) {
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
                .uri("/b/api/properties")
                .retrieve()
                .bodyToMono(PropertiesResponse.class)
                .map(body -> normalize("숙소 목록", body, SupplierBMapper::toProperties))
                // 본문이 null 로 디코딩되면 위 map 이 아예 불리지 않는다. 그대로 두면 이 Mono 가
                // 값 없이 끝나 "어떤 경우에도 결과를 돌려준다"는 포트 계약이 깨진다.
                .defaultIfEmpty(noBody("숙소 목록"))
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
            // A 와 같은 이유로 예외가 아니라 실패 값이다. 이것도 이 공급사 한 곳의 실패이고,
            // 예외로 나가면 여러 공급사를 병합하는 쪽에서 그 하나 때문에 흐름이 끊긴다.
            return Mono.just(failure("재고·요금", FailureReason.INVALID_REQUEST,
                    "묶음 크기 초과: %d > %d".formatted(propertyCodes.size(), maxBatchSize())));
        }

        return webClient.get()
                .uri(uri -> uri.path("/b/api/search")
                        .queryParam("propertyIds", String.join(",", propertyCodes))
                        .queryParam("checkIn", criteria.dates().checkIn())
                        .queryParam("checkOut", criteria.dates().checkOut())
                        .queryParam("adults", criteria.adults())
                        .queryParam("children", criteria.children())
                        .build())
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .map(body -> normalize("재고·요금", body, SupplierBMapper::toOffers))
                .defaultIfEmpty(noBody("재고·요금"))
                .timeout(properties.responseTimeout())
                .onErrorResume(error -> Mono.just(toFailure("재고·요금", error)));
    }

    /**
     * 봉투의 결과 코드를 먼저 보고, 성공일 때만 표준 모델로 옮긴다.
     *
     * <p><b>순서가 중요하다.</b> 코드를 안 보고 옮기면 장애 응답({@code data: null})이 빈 목록으로
     * 변해 "성공했는데 결과가 없다"가 된다. 실패가 조용히 사라지는 자리다.
     *
     * <p>{@code body} 가 null 인 경우는 여기서 다루지 않는다. Reactor 가 null 을 내보내지 않으므로
     * 그 경우는 값이 아예 없는 것으로 나타나고, {@code defaultIfEmpty} 가 받는다.
     */
    private <B extends Envelope, T> SupplierFetchResult<T> normalize(
            String operation, B body, Function<B, T> mapper) {

        if (!SupplierBResults.succeeded(body.resultCode())) {
            // 상태 코드는 200 이지만 실패다. 바깥에는 정규화된 사유만 나간다.
            return failure(operation, SupplierBResults.reasonOf(body.resultCode()),
                    "resultCode=" + body.resultCode());
        }
        return SupplierFetchResult.success(mapper.apply(body));
    }

    private <T> SupplierFetchResult<T> noBody(String operation) {
        return failure(operation, FailureReason.MALFORMED_RESPONSE, "본문 없음");
    }

    private <T> SupplierFetchResult<T> toFailure(String operation, Throwable error) {
        return failure(operation, HttpFailures.fromThrowable(error), HttpFailures.describe(error));
    }

    private <T> SupplierFetchResult<T> failure(String operation, FailureReason reason, String detail) {
        log.warn("공급사 {} {} 조회 실패: reason={} detail={}", SUPPLIER_CODE, operation, reason, detail);
        return SupplierFetchResult.failure(reason, detail);
    }
}
