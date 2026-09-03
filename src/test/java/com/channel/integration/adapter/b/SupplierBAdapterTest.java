package com.channel.integration.adapter.b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.domain.Availability;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;

import reactor.core.publisher.Mono;

/**
 * 실제 네트워크 없이 {@link ExchangeFunction} 을 갈아끼워 검증한다.
 *
 * <p>A 와 확인 대상이 같지만 <b>실패를 알아채는 방법이 다르다.</b> A 는 상태 코드를 보면 되는데
 * B 는 장애에도 200 이 오므로 본문 코드를 봐야 한다. 그 판정이 실제로 도는지가 이 파일의 핵심이다.
 */
class SupplierBAdapterTest {

    private static final SearchCriteria CRITERIA = new SearchCriteria(
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private static SupplierBProperties properties() {
        return new SupplierBProperties(
                "http://supplier-b.test", "key", Duration.ofSeconds(2), Duration.ofSeconds(3), 50);
    }

    private static SupplierBAdapter adapterReturning(ExchangeFunction exchange) {
        WebClient client = WebClient.builder()
                .baseUrl("http://supplier-b.test")
                .exchangeFunction(exchange)
                .build();
        return new SupplierBAdapter(client, properties());
    }

    private static SupplierBAdapter adapterWithJson(HttpStatus status, String body) {
        return adapterReturning(request -> Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build()));
    }

    @Test
    @DisplayName("묶음 크기 상한이 1 미만이면 설정 자체가 만들어지지 않는다")
    void rejectsUnusableBatchSize() {
        assertThatThrownBy(() -> new SupplierBProperties(
                "http://supplier-b.test", "key", Duration.ofSeconds(2), Duration.ofSeconds(3), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("묶음 크기 상한");
    }

    // ── 정상 응답 ────────────────────────────────────────────────

    @Nested
    @DisplayName("재고·요금 정상 응답")
    class Offers {

        private static final String BODY = """
                {
                  "resultCode": "0000",
                  "resultMessage": "SUCCESS",
                  "data": {
                    "items": [
                      {
                        "propertyId": "B77120",
                        "propertyName": "Riverside Hotel Seoul",
                        "roomId": "R-401",
                        "roomName": "Deluxe Twin Room",
                        "maxOccupancy": 2,
                        "breakfastIncluded": true,
                        "currency": "KRW",
                        "totalPrice": 452000,
                        "taxIncluded": true,
                        "inventory": [
                          { "date": "2026-09-01", "remainingRooms": 3 },
                          { "date": "2026-09-02", "remainingRooms": 1 },
                          { "date": "2026-09-03", "remainingRooms": 5 }
                        ]
                      }
                    ]
                  }
                }""";

        @Test
        @DisplayName("기간 총액을 그대로 표준 총액으로 삼는다")
        void usesTotalAsStandardPrice() {
            SupplierOffer offer = adapterWithJson(HttpStatus.OK, BODY)
                    .fetchOffers(List.of("B77120"), CRITERIA).block()
                    .asOptional().orElseThrow().getFirst();

            assertThat(offer.price().totalAmount()).isEqualTo(Money.of(452_000, "KRW"));
        }

        @Test
        @DisplayName("날짜별 내역과 세액은 없다 — 총액을 날짜 수로 쪼개지 않는다")
        void hasNoBreakdownNorTax() {
            SupplierOffer offer = adapterWithJson(HttpStatus.OK, BODY)
                    .fetchOffers(List.of("B77120"), CRITERIA).block()
                    .asOptional().orElseThrow().getFirst();

            assertThat(offer.price().hasNightlyBreakdown()).isFalse();
            assertThat(offer.price().tax()).isEmpty();
        }

        @Test
        @DisplayName("조식 포함 여부와 재고를 함께 옮긴다")
        void keepsConditionAndInventory() {
            SupplierOffer offer = adapterWithJson(HttpStatus.OK, BODY)
                    .fetchOffers(List.of("B77120"), CRITERIA).block()
                    .asOptional().orElseThrow().getFirst();

            assertThat(offer.breakfastIncluded()).isTrue();
            assertThat(offer.propertyCode()).isEqualTo("B77120");
            assertThat(offer.roomTypeCode()).isEqualTo("R-401");
            // 재고 3/1/5 → 연박 기준 1실.
            assertThat(Availability.forStay(offer.inventories(), CRITERIA.dates()).availableRooms())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("숙소 코드를 쉼표로 묶어 보낸다")
        void joinsPropertyCodes() {
            StringBuilder query = new StringBuilder();
            SupplierBAdapter adapter = adapterReturning(request -> {
                query.append(request.url().getQuery());
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(BODY)
                        .build());
            });

            adapter.fetchOffers(List.of("B77120", "B77121"), CRITERIA).block();

            assertThat(query.toString()).contains("propertyIds=B77120,B77121");
            assertThat(query.toString()).contains("checkIn=2026-09-01").contains("checkOut=2026-09-04");
        }

        @Test
        @DisplayName("조회할 숙소가 없으면 호출하지 않고 빈 성공을 준다")
        void doesNotCallWhenNoCodes() {
            SupplierBAdapter adapter = adapterReturning(request -> {
                throw new AssertionError("호출하면 안 된다");
            });

            var result = adapter.fetchOffers(List.of(), CRITERIA).block();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.asOptional().orElseThrow()).isEmpty();
        }

        @Test
        @DisplayName("묶음 크기를 넘기면 호출하지 않고 실패 값으로 돌려준다")
        void rejectsOversizedBatch() {
            SupplierBAdapter adapter = adapterReturning(request -> {
                throw new AssertionError("호출하면 안 된다");
            });
            List<String> tooMany = IntStream.rangeClosed(1, 51).mapToObj(i -> "B" + i).toList();

            var result = adapter.fetchOffers(tooMany, CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.INVALID_REQUEST);
        }

        @Test
        @DisplayName("숙소 목록을 표준 모델로 옮긴다")
        void fetchesProperties() {
            String body = """
                    {
                      "resultCode": "0000",
                      "data": { "items": [
                        { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                          "rooms": [ { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 } ] }
                      ] }
                    }""";

            var properties = adapterWithJson(HttpStatus.OK, body)
                    .fetchProperties().block().asOptional().orElseThrow();

            assertThat(properties).hasSize(1);
            assertThat(properties.getFirst().propertyCode()).isEqualTo("B77120");
            assertThat(properties.getFirst().roomTypes()).hasSize(1);
            assertThat(properties.getFirst().roomTypes().getFirst().roomTypeCode()).isEqualTo("R-401");
        }
    }

    // ── B 는 실패를 본문 코드로 알린다 ───────────────────────────

    @Nested
    @DisplayName("B 는 장애에도 HTTP 200 을 준다")
    class Failures {

        @Test
        @DisplayName("200 이지만 결과 코드가 실패면 공급사 오류로 분류한다")
        void bodyCodeDecidesFailure() {
            String outage = """
                    {"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}""";

            var result = adapterWithJson(HttpStatus.OK, outage)
                    .fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.failureReason()).contains(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("장애 응답이 '성공했는데 결과 없음'으로 새어 나가지 않는다")
        void outageIsNotAnEmptySuccess() {
            // data 가 null 이므로 코드를 안 보고 옮기면 빈 목록이 되어 실패가 조용히 사라진다.
            String outage = """
                    {"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}""";

            var result = adapterWithJson(HttpStatus.OK, outage)
                    .fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.asOptional()).isEmpty();
        }

        @Test
        @DisplayName("숙소 목록도 같은 방식으로 실패를 알아챈다")
        void appliesToPropertiesToo() {
            var result = adapterWithJson(HttpStatus.OK, """
                    {"resultCode":"E500","data":null}""").fetchProperties().block();

            assertThat(result.failureReason()).contains(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("결과 코드를 A 의 상태 코드와 같은 해상도로 나눈다")
        void mapsEachResultCodeToItsOwnReason() {
            // 통지 방식이 달라도 담기는 정보가 줄지 않아야 "같은 실패로 정규화했다"가 성립한다.
            assertThat(reasonOf("E400")).contains(FailureReason.INVALID_REQUEST);
            assertThat(reasonOf("E401")).contains(FailureReason.UNAUTHORIZED);
            assertThat(reasonOf("E429")).contains(FailureReason.RATE_LIMITED);
            assertThat(reasonOf("E500")).contains(FailureReason.SUPPLIER_ERROR);
            assertThat(reasonOf("E503")).contains(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("모르는 코드는 아는 사유로 밀어 넣지 않는다")
        void unknownCodeStaysUnknown() {
            assertThat(reasonOf("E999")).contains(FailureReason.UNKNOWN);
        }

        private java.util.Optional<FailureReason> reasonOf(String code) {
            return adapterWithJson(HttpStatus.OK, """
                    {"resultCode":"%s","data":null}""".formatted(code))
                    .fetchOffers(List.of("B77120"), CRITERIA).block().failureReason();
        }

        @Test
        @DisplayName("결과 코드가 없으면 봉투가 계약을 안 지킨 것이라 변환 실패다")
        void missingResultCodeIsMalformed() {
            var result = adapterWithJson(HttpStatus.OK, """
                    {"data":{"items":[]}}""").fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.MALFORMED_RESPONSE);
        }

        @Test
        @DisplayName("성공 코드인데 data 가 비면 결과가 없는 것으로 본다 — 실패가 아니다")
        void emptyDataOnSuccessIsEmptyResult() {
            var result = adapterWithJson(HttpStatus.OK, """
                    {"resultCode":"0000","data":null}""")
                    .fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.asOptional().orElseThrow()).isEmpty();
        }

        @Test
        @DisplayName("전송 계층 실패는 상태 코드로 판정한다 — 본문 코드 판정을 대신하지 않는다")
        void transportFailuresStillUseStatusCode() {
            // B 도 인프라가 낸 5xx 나 인증 실패는 상태 코드로 온다. 두 층이 다 필요하다.
            assertThat(adapterWithJson(HttpStatus.SERVICE_UNAVAILABLE, "{}")
                    .fetchOffers(List.of("B77120"), CRITERIA).block().failureReason())
                    .contains(FailureReason.SUPPLIER_ERROR);
            assertThat(adapterWithJson(HttpStatus.UNAUTHORIZED, "{}")
                    .fetchOffers(List.of("B77120"), CRITERIA).block().failureReason())
                    .contains(FailureReason.UNAUTHORIZED);
            assertThat(adapterWithJson(HttpStatus.TOO_MANY_REQUESTS, "{}")
                    .fetchOffers(List.of("B77120"), CRITERIA).block().failureReason())
                    .contains(FailureReason.RATE_LIMITED);
        }

        @Test
        @DisplayName("실패해도 예외를 밖으로 던지지 않는다")
        void neverThrows() {
            SupplierBAdapter adapter = adapterReturning(
                    request -> Mono.error(new IllegalStateException("전송 실패")));

            var result = adapter.fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.failureReason()).isPresent();
        }

        @Test
        @DisplayName("응답이 늦으면 타임아웃으로 분류한다")
        void timeout() {
            SupplierBAdapter adapter = adapterReturning(request ->
                    Mono.just(ClientResponse.create(HttpStatus.OK)
                                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                    .body("{\"resultCode\":\"0000\"}")
                                    .build())
                            .delayElement(Duration.ofSeconds(5)));

            var result = adapter.fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.TIMEOUT);
        }

        @Test
        @DisplayName("해석할 수 없는 본문은 변환 실패로 분류한다")
        void malformedBody() {
            var result = adapterWithJson(HttpStatus.OK, "{ this is not json ")
                    .fetchOffers(List.of("B77120"), CRITERIA).block();

            assertThat(result.failureReason()).contains(FailureReason.MALFORMED_RESPONSE);
        }
    }

    // ── 정규화 경계 ──────────────────────────────────────────────

    @Test
    @DisplayName("세금 포함 총액이 아니면 그 상품을 만들지 않는다 — 세액을 지어내지 않는다")
    void skipsWhenTotalIsNotGross() {
        String body = """
                {
                  "resultCode": "0000",
                  "data": { "items": [
                    { "propertyId": "B77120", "roomId": "R-401", "maxOccupancy": 2,
                      "currency": "KRW", "totalPrice": 400000, "taxIncluded": false,
                      "inventory": [ { "date": "2026-09-01", "remainingRooms": 3 } ] },
                    { "propertyId": "B77121", "roomId": "R-402", "maxOccupancy": 2,
                      "currency": "KRW", "totalPrice": 452000, "taxIncluded": true,
                      "inventory": [ { "date": "2026-09-01", "remainingRooms": 3 } ] }
                  ] }
                }""";

        List<SupplierOffer> offers = adapterWithJson(HttpStatus.OK, body)
                .fetchOffers(List.of("B77120", "B77121"), CRITERIA).block()
                .asOptional().orElseThrow();

        // 한 건을 못 옮겨도 나머지는 살린다.
        assertThat(offers).hasSize(1);
        assertThat(offers.getFirst().propertyCode()).isEqualTo("B77121");
    }

    @Test
    @DisplayName("변환할 수 없는 항목은 건너뛰고 나머지는 살린다")
    void skipsUnmappableItemsOnly() {
        String body = """
                {
                  "resultCode": "0000",
                  "data": { "items": [
                    { "propertyId": "B-BROKEN", "roomId": "X", "maxOccupancy": 2, "currency": "KRW" },
                    { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                      "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2,
                      "breakfastIncluded": true, "currency": "KRW",
                      "totalPrice": 452000, "taxIncluded": true,
                      "inventory": [ { "date": "2026-09-01", "remainingRooms": 3 } ] }
                  ] }
                }""";

        SupplierFetchResult<List<SupplierOffer>> result = adapterWithJson(HttpStatus.OK, body)
                .fetchOffers(List.of("B77120"), CRITERIA).block();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.asOptional().orElseThrow()).hasSize(1);
    }
}
