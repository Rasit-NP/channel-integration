package com.channel.integration.adapter.b;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.adapter.support.SupplierWebClients;
import com.channel.integration.domain.Availability;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.SupplierOffer;
import com.channel.mock.MockSupplierApplication;

/**
 * 실제 Mock 공급사를 띄우고 HTTP 로 호출한다. {@code SupplierAMockIntegrationTest} 와 짝이다.
 *
 * <p><b>두 파일을 나란히 놓고 보는 것이 요점이다.</b> 같은 "장애 모드"에서 A 는 503 을 받아
 * 실패로 분류하고 B 는 200 을 받아 실패로 분류한다. 통지 방식이 다른데 바깥에 나가는 사유는
 * 같은 {@link FailureReason} 이다 — 그게 실패 판정을 통일했다는 말의 실제 내용이다.
 */
@SpringBootTest(
        classes = MockSupplierApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupplierBMockIntegrationTest {

    @LocalServerPort
    private int mockPort;

    private static final SearchCriteria CRITERIA = new SearchCriteria(
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private SupplierBAdapter adapter(Duration responseTimeout) {
        SupplierBProperties properties = new SupplierBProperties(
                "http://localhost:" + mockPort, "local-dev-key",
                Duration.ofSeconds(2), responseTimeout, 50);
        return new SupplierBAdapter(
                SupplierWebClients.create(WebClient.builder(), properties), properties);
    }

    private SupplierBAdapter adapter() {
        return adapter(Duration.ofSeconds(3));
    }

    private void setMode(String mode) {
        WebClient.create("http://localhost:" + mockPort)
                .post().uri(uri -> uri.path("/control/b/mode").queryParam("value", mode).build())
                .retrieve().bodyToMono(String.class).block();
    }

    @AfterEach
    void restoreNormalMode() {
        setMode("normal");
    }

    @Test
    @DisplayName("숙소 목록을 실제 HTTP 로 받아 표준 모델로 옮긴다")
    void fetchesPropertiesOverHttp() {
        var result = adapter().fetchProperties().block();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.asOptional().orElseThrow())
                .extracting("propertyCode")
                .containsExactly("B77120");
    }

    @Test
    @DisplayName("총액만 주는 공급사의 요금을 표준 형태로 받는다")
    void fetchesOffersOverHttp() {
        var result = adapter().fetchOffers(List.of("B77120"), CRITERIA).block();

        assertThat(result.isSuccess()).isTrue();
        SupplierOffer offer = result.asOptional().orElseThrow().getFirst();

        assertThat(offer.price().totalAmount()).isEqualTo(Money.of(452_000, "KRW"));
        assertThat(offer.price().hasNightlyBreakdown()).isFalse();
        assertThat(offer.price().tax()).isEmpty();
        assertThat(offer.breakfastIncluded()).isTrue();
        assertThat(Availability.forStay(offer.inventories(), CRITERIA.dates()).availableRooms())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("장애 모드 — B 는 HTTP 200 을 주지만 본문 코드로 실패를 알리고, 같은 사유로 분류된다")
    void supplierErrorMode() {
        setMode("error");

        var result = adapter().fetchOffers(List.of("B77120"), CRITERIA).block();

        // A 의 같은 테스트는 503 을 받는다. 통지 방식이 달라도 사유는 같다.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).contains(FailureReason.SUPPLIER_ERROR);
    }

    @Test
    @DisplayName("무응답 모드 — 설정한 응답 타임아웃 안에 끊고 TIMEOUT 으로 분류한다")
    void noResponseMode() {
        setMode("no-response");

        long startedAt = System.currentTimeMillis();
        var result = adapter(Duration.ofMillis(700)).fetchOffers(List.of("B77120"), CRITERIA).block();
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).contains(FailureReason.TIMEOUT);
        assertThat(elapsed).isLessThan(3_000);
    }

    @Test
    @DisplayName("공급사에 연결되지 않아도 예외를 던지지 않고 실패 값을 준다")
    void connectionFailureBecomesValue() {
        SupplierBProperties unreachable = new SupplierBProperties(
                "http://localhost:1", "key", Duration.ofMillis(500), Duration.ofSeconds(1), 50);
        SupplierBAdapter adapter = new SupplierBAdapter(
                SupplierWebClients.create(WebClient.builder(), unreachable), unreachable);

        var result = adapter.fetchOffers(List.of("B77120"), CRITERIA).block();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).isPresent();
    }
}
