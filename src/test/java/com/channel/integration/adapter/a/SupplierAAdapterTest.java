package com.channel.integration.adapter.a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;

import reactor.core.publisher.Mono;

/**
 * 실제 네트워크 없이 {@link ExchangeFunction} 을 갈아끼워 검증한다. 확인 대상은 세 가지다 —
 * 응답을 표준 모델로 옳게 옮기는가, 실패를 사유로 정규화하는가, 예외를 밖으로 흘리지 않는가.
 */
class SupplierAAdapterTest {

    private static final SearchCriteria CRITERIA = new SearchCriteria(
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private static SupplierAProperties properties() {
        return new SupplierAProperties(
                "http://supplier-a.test", "key", Duration.ofSeconds(2), Duration.ofSeconds(3), 50);
    }

    private static SupplierAAdapter adapterReturning(ExchangeFunction exchange) {
        WebClient client = WebClient.builder()
                .baseUrl("http://supplier-a.test")
                .exchangeFunction(exchange)
                .build();
        return new SupplierAAdapter(client, properties());
    }

    private static SupplierAAdapter adapterWithJson(HttpStatus status, String body) {
        return adapterReturning(request -> Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build()));
    }

    // ── 정상 응답 ────────────────────────────────────────────────

    @Nested
    @DisplayName("재고·요금 정상 응답")
    class Offers {

        private static final String BODY = """
                {
                  "items": [
                    {
                      "hotelCode": "A-10023",
                      "hotelName": "Riverside Hotel Seoul",
                      "roomTypeCode": "DLX-TWN",
                      "roomTypeName": "Deluxe Twin",
                      "maxOccupancy": 2,
                      "breakfastIncluded": false,
                      "currency": "KRW",
                      "dailyRates": [
                        { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                        { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                        { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
                      ]
                    }
                  ]
                }""";

        @Test
        @DisplayName("날짜별 단가를 세금 포함 총액으로 정규화한다")
        void normalizesToGrossTotal() {
            SupplierFetchResult<List<SupplierOffer>> result =
                    adapterWithJson(HttpStatus.OK, BODY).fetchOffers(List.of("A-10023"), CRITERIA).block();

            assertThat(result.isSuccess()).isTrue();
            SupplierOffer offer = result.asOptional().orElseThrow().getFirst();
            assertThat(offer.price().totalAmount()).isEqualTo(Money.of(429_000, "KRW"));
            assertThat(offer.price().tax()).contains(Money.of(39_000, "KRW"));
        }

        @Test
        @DisplayName("날짜별 내역과 재고를 함께 옮긴다")
        void keepsBreakdownAndInventory() {
            SupplierOffer offer = adapterWithJson(HttpStatus.OK, BODY)
                    .fetchOffers(List.of("A-10023"), CRITERIA).block()
                    .asOptional().orElseThrow().getFirst();

            assertThat(offer.price().hasNightlyBreakdown()).isTrue();
            assertThat(offer.inventories()).hasSize(3);
            assertThat(offer.breakfastIncluded()).isFalse();
            assertThat(offer.propertyCode()).isEqualTo("A-10023");
            assertThat(offer.roomTypeCode()).isEqualTo("DLX-TWN");
        }

        @Test
        @DisplayName("숙소 코드를 쉼표로 묶어 보낸다")
        void joinsPropertyCodes() {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            SupplierAAdapter adapter = adapterReturning(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"items\":[]}")
                        .build());
            });

            adapter.fetchOffers(List.of("A-10023", "A-10044"), CRITERIA).block();

            String query = captured.get().url().getQuery();
            assertThat(query).contains("hotelCodes=A-10023,A-10044");
            assertThat(query).contains("checkIn=2026-09-01").contains("checkOut=2026-09-04");
            assertThat(query).contains("adults=2").contains("children=0");
        }

        @Test
        @DisplayName("조회할 숙소가 없으면 호출하지 않고 빈 성공을 준다")
        void doesNotCallWhenNoCodes() {
            SupplierAAdapter adapter = adapterReturning(request -> {
                throw new AssertionError("호출하면 안 된다");
            });

            var result = adapter.fetchOffers(List.of(), CRITERIA).block();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.asOptional().orElseThrow()).isEmpty();
        }

        @Test
        @DisplayName("묶음 크기를 넘기면 호출 전에 막는다")
        void rejectsOversizedBatch() {
            SupplierAAdapter adapter = adapterWithJson(HttpStatus.OK, "{\"items\":[]}");
            List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> "A-" + i).toList();

            assertThatThrownBy(() -> adapter.fetchOffers(tooMany, CRITERIA))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("숙소 목록을 표준 모델로 옮긴다")
    void fetchesProperties() {
        String body = """
                {
                  "items": [
                    {
                      "hotelCode": "A-10023",
                      "hotelName": "Riverside Hotel Seoul",
                      "roomTypes": [
                        { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 }
                      ]
                    }
                  ]
                }""";

        List<SupplierProperty> properties = adapterWithJson(HttpStatus.OK, body)
                .fetchProperties().block().asOptional().orElseThrow();

        assertThat(properties).hasSize(1);
        assertThat(properties.getFirst().propertyCode()).isEqualTo("A-10023");
        assertThat(properties.getFirst().roomTypes()).hasSize(1);
        assertThat(properties.getFirst().roomTypes().getFirst().maxOccupancy()).isEqualTo(2);
    }

    // ── 실패 정규화 ──────────────────────────────────────────────

    @Nested
    @DisplayName("A 는 실패를 HTTP 상태 코드로 알린다")
    class Failures {

        private SupplierFetchResult<List<SupplierOffer>> fetchWith(HttpStatus status) {
            String body = "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}";
            return adapterWithJson(status, body).fetchOffers(List.of("A-10023"), CRITERIA).block();
        }

        @Test
        @DisplayName("503 은 공급사 오류다")
        void serviceUnavailable() {
            assertThat(fetchWith(HttpStatus.SERVICE_UNAVAILABLE).failureReason())
                    .contains(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("500 은 공급사 오류다")
        void internalError() {
            assertThat(fetchWith(HttpStatus.INTERNAL_SERVER_ERROR).failureReason())
                    .contains(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("401 은 인증 실패다 — 재시도해도 소용없다")
        void unauthorized() {
            assertThat(fetchWith(HttpStatus.UNAUTHORIZED).failureReason())
                    .contains(FailureReason.UNAUTHORIZED);
        }

        @Test
        @DisplayName("429 는 호출 한도 초과다")
        void rateLimited() {
            assertThat(fetchWith(HttpStatus.TOO_MANY_REQUESTS).failureReason())
                    .contains(FailureReason.RATE_LIMITED);
        }

        @Test
        @DisplayName("400 은 우리 요청 문제다")
        void invalidRequest() {
            assertThat(fetchWith(HttpStatus.BAD_REQUEST).failureReason())
                    .contains(FailureReason.INVALID_REQUEST);
        }

        @Test
        @DisplayName("실패해도 예외를 밖으로 던지지 않는다")
        void neverThrows() {
            SupplierFetchResult<List<SupplierOffer>> result = fetchWith(HttpStatus.SERVICE_UNAVAILABLE);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.asOptional()).isEmpty();
        }

        @Test
        @DisplayName("응답이 늦으면 타임아웃으로 분류한다")
        void timeout() {
            SupplierAAdapter adapter = adapterReturning(request ->
                    Mono.just(ClientResponse.create(HttpStatus.OK)
                                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                    .body("{\"items\":[]}")
                                    .build())
                            .delayElement(Duration.ofSeconds(5))); // 응답 타임아웃 3초보다 길게

            var result = adapter.fetchOffers(List.of("A-10023"), CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.TIMEOUT);
        }

        @Test
        @DisplayName("해석할 수 없는 본문은 변환 실패로 분류한다")
        void malformedBody() {
            var result = adapterWithJson(HttpStatus.OK, "{ this is not json ")
                    .fetchOffers(List.of("A-10023"), CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.MALFORMED_RESPONSE);
        }
    }

    // ── 정규화 경계 ──────────────────────────────────────────────

    @Test
    @DisplayName("변환할 수 없는 항목은 건너뛰고 나머지는 살린다")
    void skipsUnmappableItemsOnly() {
        String body = """
                {
                  "items": [
                    { "hotelCode": "A-BROKEN", "roomTypeCode": "X", "maxOccupancy": 2, "currency": "KRW", "dailyRates": [] },
                    {
                      "hotelCode": "A-10044",
                      "hotelName": "Namsan Garden Stay",
                      "roomTypeCode": "STD-DBL",
                      "roomTypeName": "Standard Double",
                      "maxOccupancy": 2,
                      "breakfastIncluded": false,
                      "currency": "KRW",
                      "dailyRates": [
                        { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 }
                      ]
                    }
                  ]
                }""";

        List<SupplierOffer> offers = adapterWithJson(HttpStatus.OK, body)
                .fetchOffers(List.of("A-10044"), CRITERIA).block()
                .asOptional().orElseThrow();

        assertThat(offers).hasSize(1);
        assertThat(offers.getFirst().propertyCode()).isEqualTo("A-10044");
    }
}
