package com.channel.integration.adapter.a;

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
 * 실제 Mock 공급사를 띄우고 HTTP 로 호출한다.
 *
 * <p>{@link SupplierAAdapterTest} 는 응답을 갈아끼워 변환·실패 분류를 확인하지만, 전송 계층은
 * 지나가지 않는다. 여기서는 Netty 타임아웃 설정, 실제 직렬화 경로, Mock 의 모드 전환까지
 * 함께 확인한다. 둘 다 필요하다.
 */
@SpringBootTest(
        classes = MockSupplierApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupplierAMockIntegrationTest {

    @LocalServerPort
    private int mockPort;

    private static final SearchCriteria CRITERIA = new SearchCriteria(
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private SupplierAAdapter adapter(Duration responseTimeout) {
        SupplierAProperties properties = new SupplierAProperties(
                "http://localhost:" + mockPort, "local-dev-key",
                Duration.ofSeconds(2), responseTimeout, 50);
        return new SupplierAAdapter(
                SupplierWebClients.create(WebClient.builder(), properties), properties);
    }

    private SupplierAAdapter adapter() {
        return adapter(Duration.ofSeconds(3));
    }

    private void setMode(String mode) {
        WebClient.create("http://localhost:" + mockPort)
                .post().uri(uri -> uri.path("/control/a/mode").queryParam("value", mode).build())
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
                .containsExactlyInAnyOrder("A-10023", "A-10044");
    }

    @Test
    @DisplayName("재고·요금을 받아 세금 포함 총액과 연박 재고까지 판정한다")
    void fetchesOffersOverHttp() {
        var result = adapter().fetchOffers(List.of("A-10023", "A-10044"), CRITERIA).block();

        assertThat(result.isSuccess()).isTrue();
        List<SupplierOffer> offers = result.asOptional().orElseThrow();
        assertThat(offers).hasSize(2);

        SupplierOffer riverside = offers.stream()
                .filter(o -> o.propertyCode().equals("A-10023")).findFirst().orElseThrow();
        assertThat(riverside.price().totalAmount()).isEqualTo(Money.of(429_000, "KRW"));
        assertThat(Availability.forStay(riverside.inventories(), CRITERIA.dates()).availableRooms())
                .isEqualTo(1);

        // 09-02 재고가 0 이므로 3박 연속 예약이 불가능하다.
        SupplierOffer namsan = offers.stream()
                .filter(o -> o.propertyCode().equals("A-10044")).findFirst().orElseThrow();
        assertThat(Availability.forStay(namsan.inventories(), CRITERIA.dates()).bookable()).isFalse();
    }

    @Test
    @DisplayName("장애 모드 — A 는 HTTP 상태 코드로 실패를 알리고, 공급사 오류로 분류된다")
    void supplierErrorMode() {
        setMode("error");

        var result = adapter().fetchOffers(List.of("A-10023"), CRITERIA).block();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).contains(FailureReason.SUPPLIER_ERROR);
    }

    @Test
    @DisplayName("무응답 모드 — 설정한 응답 타임아웃 안에 끊고 TIMEOUT 으로 분류한다")
    void noResponseMode() {
        setMode("no-response");

        long startedAt = System.currentTimeMillis();
        var result = adapter(Duration.ofMillis(700)).fetchOffers(List.of("A-10023"), CRITERIA).block();
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).contains(FailureReason.TIMEOUT);
        // 공급사가 응답하지 않아도 우리 쪽에서 먼저 끊는다.
        assertThat(elapsed).isLessThan(3_000);
    }

    @Test
    @DisplayName("공급사에 연결되지 않아도 예외를 던지지 않고 실패 값을 준다")
    void connectionFailureBecomesValue() {
        SupplierAProperties unreachable = new SupplierAProperties(
                "http://localhost:1", "key", Duration.ofMillis(500), Duration.ofSeconds(1), 50);
        SupplierAAdapter adapter = new SupplierAAdapter(
                SupplierWebClients.create(WebClient.builder(), unreachable), unreachable);

        var result = adapter.fetchOffers(List.of("A-10023"), CRITERIA).block();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failureReason()).isPresent();
    }
}
