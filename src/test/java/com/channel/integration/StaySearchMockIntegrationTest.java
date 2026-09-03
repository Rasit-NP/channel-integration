package com.channel.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
        "supplier.b.base-url=http://localhost:19090",
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

    /**
     * 우리 엔드포인트를 실제 HTTP 로 부르는 테스트 도구다. <b>공급사 연동에 쓰는 클라이언트가
     * 아니다</b> — 공급사 호출은 어댑터가 WebClient 로만 한다.
     */
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
        setMode("a", "normal");
        setMode("b", "normal");
    }

    @Test
    @DisplayName("동기화부터 검색 응답까지 한 번에 통과한다")
    void completesWholeFlow() {
        sync.synchronize();

        ResponseEntity<String> response = rest.getForEntity(SEARCH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        // 두 공급사에서 하나씩 왔다. 재고 0 이 낀 A-10044 는 빠진다.
        assertThat(read(body, "$.stays.length()")).isEqualTo(2);
        assertThat(read(body, "$.excludedSoldOut")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(body, "$.partial")).isFalse();

        // 재고가 3 / 1 / 5 인 상품은 연박 기준 1실이 남는다. 병목이 되는 하루가 전체를 결정한다.
        assertThat(pick(body, "A", "availableRooms")).isEqualTo(1);

        // 공급사 코드가 아니라 내부 식별자가 나간다.
        assertThat((Integer) pick(body, "A", "propertyId")).isPositive();
        assertThat((Integer) pick(body, "A", "roomTypeId")).isPositive();
    }

    @Test
    @DisplayName("표현이 다른 두 공급사의 요금이 같은 형태로 나온다")
    void normalizesBothSuppliersIntoOneShape() {
        sync.synchronize();

        String body = rest.getForEntity(SEARCH, String.class).getBody();

        // A 는 날짜별 (단가 + 세액)을 합친 총액이고, 내역과 세액을 함께 준다.
        assertThat(pick(body, "A", "price.totalAmount")).isEqualTo(429_000);
        assertThat(JsonPath.<List<Object>>read(body, stay("A") + ".price.nightlyRates[*]")).hasSize(3);
        assertThat(pick(body, "A", "price.taxAmount")).isEqualTo(39_000);

        // B 는 기간 총액만 준다. 없는 세액과 내역은 0 이나 빈 배열이 아니라 필드 자체가 없다.
        assertThat(pick(body, "B", "price.totalAmount")).isEqualTo(452_000);
        assertThat(JsonPath.<List<Object>>read(body, stay("B") + ".price.taxAmount")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(body, stay("B") + ".price.nightlyRates")).isEmpty();

        // 같은 이름의 숙소지만 합치지 않는다. 조건이 다른 두 상품으로 나가고,
        // 무엇이 포함된 값인지가 요금과 함께 보인다.
        assertThat(JsonPath.<List<String>>read(body, "$.stays[*].propertyName"))
                .containsExactlyInAnyOrder("Riverside Hotel Seoul", "Riverside Hotel Seoul");
        assertThat(pick(body, "A", "breakfastIncluded")).isEqualTo(false);
        assertThat(pick(body, "B", "breakfastIncluded")).isEqualTo(true);
        assertThat(pick(body, "A", "propertyId")).isNotEqualTo(pick(body, "B", "propertyId"));
    }

    @Test
    @DisplayName("한 공급사가 죽어도 나머지 공급사의 결과로 응답한다")
    void survivesOneSupplierOutage() {
        sync.synchronize();
        setMode("a", "error");

        ResponseEntity<String> response = rest.getForEntity(SEARCH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        // A 는 못 봤지만 B 결과는 그대로 나간다. 이것이 부분 실패를 허용한다는 말의 내용이다.
        assertThat(read(body, "$.stays.length()")).isEqualTo(1);
        assertThat(pick(body, "B", "supplier")).isEqualTo("B");

        assertThat(JsonPath.<Boolean>read(body, "$.partial")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.suppliers[?(@.supplier=='A')].status"))
                .containsExactly("FAILED");
        assertThat(JsonPath.<List<String>>read(body, "$.suppliers[?(@.supplier=='A')].reason"))
                .containsExactly("SUPPLIER_ERROR");
        assertThat(JsonPath.<List<String>>read(body, "$.suppliers[?(@.supplier=='B')].status"))
                .containsExactly("OK");

        // 결과가 준 이유가 구분된다 — 만실이라 뺀 게 아니라 못 본 것이다.
        assertThat(read(body, "$.excludedSoldOut")).isZero();
    }

    @Test
    @DisplayName("실패를 알리는 방식이 달라도 같은 사유로 정규화된다")
    void normalizesBothFailureStyles() {
        sync.synchronize();
        // A 는 503 으로, B 는 200 + 본문 코드로 실패를 알린다.
        setMode("a", "error");
        setMode("b", "error");

        String body = rest.getForEntity(SEARCH, String.class).getBody();

        assertThat(read(body, "$.stays.length()")).isZero();
        assertThat(JsonPath.<Boolean>read(body, "$.partial")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.suppliers[*].reason"))
                .containsExactlyInAnyOrder("SUPPLIER_ERROR", "SUPPLIER_ERROR");
    }

    // 매핑이 비었을 때 공급사를 부르지 않는다는 것은 StaySearchServiceTest 가 본다. 여기서
    // 확인하려면 다른 테스트가 채워둔 매핑에 영향을 받아 실행 순서에 묶인다.

    /** 응답의 공급사 순서는 정해져 있지 않으므로 자리가 아니라 출처로 찾는다. */
    private static String stay(String supplier) {
        return "$.stays[?(@.supplier=='%s')]".formatted(supplier);
    }

    private static Object pick(String body, String supplier, String field) {
        List<Object> found = JsonPath.read(body, stay(supplier) + "." + field);
        assertThat(found).as("공급사 %s 의 %s", supplier, field).hasSize(1);
        return found.getFirst();
    }

    private static int read(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static void setMode(String supplier, String mode) {
        WebClient.create("http://localhost:" + MOCK_PORT)
                .post().uri(uri -> uri.path("/control/" + supplier + "/mode")
                        .queryParam("value", mode).build())
                .retrieve().bodyToMono(String.class).block();
    }
}
