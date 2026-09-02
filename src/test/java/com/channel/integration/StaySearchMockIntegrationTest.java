package com.channel.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.application.PropertySyncService;
import com.channel.mock.MockSupplierApplication;
import com.jayway.jsonpath.JsonPath;

/**
 * 공급사 목록 조회부터 고객 응답까지 한 번에 통과시킨다.
 *
 * <p>단위 테스트는 각 조각이 짠 대로 도는지를 보지만, 조각을 이어 붙였을 때 실제로 도는지는
 * 보여주지 못한다. 여기서는 <b>Mock 을 진짜 HTTP 로 세우고</b> 동기화 → 매핑 저장 → 병렬 조회 →
 * 정규화 → 병합 → JSON 응답까지 실제로 관통시킨다.
 *
 * <p>Mock 은 본 애플리케이션과 다른 포트에 둔다. 같은 포트에 두면 자기 자신을 부르게 되어,
 * 스레드가 묶이면서 연동 문제로 오해하기 쉬운 실패가 생긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "supplier.a.base-url=http://localhost:19090",
        // 개발용 데이터 파일을 건드리지 않는다.
        "spring.datasource.url=jdbc:h2:mem:search-e2e;DB_CLOSE_DELAY=-1",
        // 동기화 시점을 테스트가 정한다. 기동 훅에 의존하면 무엇을 보고 있는지 흐려진다.
        "supplier.sync.on-startup=false"
})
class StaySearchMockIntegrationTest {

    private static final int MOCK_PORT = 19090;
    private static final String SEARCH =
            "/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0";

    private static ConfigurableApplicationContext mockSupplier;

    @Autowired
    private PropertySyncService sync;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void startMockSupplier() {
        mockSupplier = new SpringApplicationBuilder(MockSupplierApplication.class)
                .properties(
                        "server.port=" + MOCK_PORT,
                        // Mock 은 저장소가 필요 없다. 테스트 클래스패스에 본 애플리케이션의
                        // 설정이 함께 올라와 있어, 두지 않으면 DB 까지 띄우려 든다.
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration")
                .run();
    }

    @AfterAll
    static void stopMockSupplier() {
        if (mockSupplier != null) {
            mockSupplier.close();
        }
    }

    @BeforeEach
    void restoreNormalMode() {
        setMode("normal");
    }

    @Test
    @DisplayName("동기화부터 검색 응답까지 한 번에 통과한다")
    void completesWholeFlow() {
        sync.synchronize();

        ResponseEntity<String> response = rest.getForEntity(SEARCH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        // 재고가 3 / 1 / 5 인 상품은 연박 기준 1실이 남는다. 병목이 되는 하루가 전체를 결정한다.
        assertThat(read(body, "$.stays.length()")).isEqualTo(1);
        assertThat(read(body, "$.stays[0].availableRooms")).isEqualTo(1);
        assertThat(JsonPath.<String>read(body, "$.stays[0].supplier")).isEqualTo("A");

        // 날짜별 (단가 + 세액)을 합친 세금 포함 총액이다.
        assertThat(read(body, "$.stays[0].price.totalAmount")).isEqualTo(429_000);
        assertThat(read(body, "$.stays[0].price.nightlyRates.length()")).isEqualTo(3);

        // 공급사 코드가 아니라 내부 식별자가 나간다.
        assertThat((Integer) JsonPath.read(body, "$.stays[0].propertyId")).isPositive();
        assertThat((Integer) JsonPath.read(body, "$.stays[0].roomTypeId")).isPositive();

        // 재고 0 인 하루가 낀 상품은 빠지되, 빠졌다는 사실은 남는다.
        assertThat(read(body, "$.excludedSoldOut")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(body, "$.partial")).isFalse();
    }

    @Test
    @DisplayName("공급사가 장애면 200 으로 응답하되 부분 실패임을 알린다")
    void reportsSupplierOutageWithoutFailingRequest() {
        sync.synchronize();
        setMode("error");

        ResponseEntity<String> response = rest.getForEntity(SEARCH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(read(body, "$.stays.length()")).isZero();
        assertThat(JsonPath.<Boolean>read(body, "$.partial")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.suppliers[0].status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(body, "$.suppliers[0].reason")).isEqualTo("SUPPLIER_ERROR");

        // 결과가 빈 이유가 구분된다 — 만실이라 뺀 게 아니라 못 본 것이다.
        assertThat(read(body, "$.excludedSoldOut")).isZero();
    }

    // 매핑이 비었을 때 공급사를 부르지 않는다는 것은 StaySearchServiceTest 가 본다. 여기서
    // 확인하려면 다른 테스트가 채워둔 매핑에 영향을 받아 실행 순서에 묶인다.

    private static int read(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static void setMode(String mode) {
        WebClient.create("http://localhost:" + MOCK_PORT)
                .post().uri(uri -> uri.path("/control/a/mode").queryParam("value", mode).build())
                .retrieve().bodyToMono(String.class).block();
    }
}
