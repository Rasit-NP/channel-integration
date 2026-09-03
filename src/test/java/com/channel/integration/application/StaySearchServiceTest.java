package com.channel.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.channel.integration.domain.DailyInventory;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.Stay;
import com.channel.integration.domain.StayPrice;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

import reactor.core.publisher.Mono;

/**
 * 검색 조립을 본다 — 나누고, 부르고, 정체를 확정하고, 뺄 것을 빼는 네 단계.
 *
 * <p>확인의 초점은 <b>결과가 비었을 때 왜 비었는지 구분되는가</b>다. 전부 만실인 것, 공급사를
 * 못 본 것, 매핑이 모자란 것은 서로 다른 사건이고 클라이언트가 할 일도 다르다.
 */
class StaySearchServiceTest {

    private static final SupplierCode A = SupplierCode.of("A");
    private static final SupplierCode B = SupplierCode.of("B");

    private static final LocalDate DAY1 = LocalDate.parse("2026-09-01");
    private static final LocalDate DAY2 = LocalDate.parse("2026-09-02");
    private static final LocalDate DAY3 = LocalDate.parse("2026-09-03");

    private static final SearchCriteria CRITERIA = new SearchCriteria(
            DateRange.of(DAY1, LocalDate.parse("2026-09-04")), 2, 0);

    private final InMemoryMappingRepository repository = new InMemoryMappingRepository();

    @BeforeEach
    void mapProperties() {
        repository.register(A, List.of(property("A-10023", "DLX-TWN"), property("A-10044", "STD-DBL")));
        repository.register(B, List.of(property("B77120", "R-401")));
    }

    // ── 나누기 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("묶음 분할")
    class Batching {

        @Test
        @DisplayName("공급사가 선언한 한계에 맞춰 나눠 부른다")
        void splitsBySupplierLimit() {
            repository.register(A, List.of(
                    property("A-3", "R"), property("A-4", "R"), property("A-5", "R")));
            StubAdapter adapter = new StubAdapter(A, 2, batch -> success());

            search(adapter);

            // 코드 5개를 한계 2로 나누면 2+2+1 이다.
            assertThat(adapter.receivedBatches()).hasSize(3);
            assertThat(adapter.receivedBatches()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(2));
            assertThat(adapter.receivedBatches().stream().flatMap(List::stream))
                    .containsExactlyInAnyOrder("A-10023", "A-10044", "A-3", "A-4", "A-5");
        }

        @Test
        @DisplayName("한계 안에 들어가면 한 번만 부른다")
        void callsOnceWhenWithinLimit() {
            StubAdapter adapter = new StubAdapter(A, 50, batch -> success());

            search(adapter);

            assertThat(adapter.receivedBatches()).hasSize(1);
        }

        @Test
        @DisplayName("어댑터가 1 미만을 선언하면 나누다 멈추지 않고 그 자리에서 드러난다")
        void rejectsUnusableBatchSize() {
            // 0 이면 나누는 반복이 끝나지 않는다. 설정은 기동 때 걸러지지만 여기도 막아 둔다.
            StubAdapter broken = new StubAdapter(A, 0, batch -> success());

            assertThatThrownBy(() -> search(broken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("묶음 크기 상한");
        }

        @Test
        @DisplayName("매핑이 없으면 공급사를 부르지 않는다")
        void doesNotCallWithoutMappings() {
            InMemoryMappingRepository empty = new InMemoryMappingRepository();
            StubAdapter adapter = new StubAdapter(A, 50, batch -> success());

            StaySearchResult result = new StaySearchService(
                    List.of(adapter), empty, new SearchProperties(4, Duration.ofSeconds(5)))
                    .search(CRITERIA);

            assertThat(adapter.receivedBatches()).isEmpty();
            assertThat(result.stays()).isEmpty();
            assertThat(result.suppliers()).isEmpty();
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("매핑은 있는데 어댑터가 없는 공급사는 건너뛴다")
        void skipsSuppliersWithoutAdapter() {
            StubAdapter onlyA = new StubAdapter(A, 50, batch -> success());

            StaySearchResult result = search(onlyA);

            // B 매핑은 있지만 어댑터가 없다. 조회하지 않았으므로 상태 목록에도 넣지 않는다.
            assertThat(result.suppliers()).extracting(status -> status.supplier().value())
                    .containsExactly("A");
        }
    }

    // ── 정체 확정과 제외 ─────────────────────────────────────────

    @Nested
    @DisplayName("정규화")
    class Normalization {

        @Test
        @DisplayName("공급사 코드를 내부 식별자로 바꾼다")
        void resolvesInternalIdentifiers() {
            long expectedProperty = repository.load().propertyId(A, "A-10023").orElseThrow();
            long expectedRoomType = repository.load().roomTypeId(A, "A-10023", "DLX-TWN").orElseThrow();

            StaySearchResult result = search(new StubAdapter(A, 50, batch ->
                    success(offer("A-10023", "DLX-TWN", 2, 3, 2, 5))));

            assertThat(result.stays()).singleElement().satisfies(stay -> {
                assertThat(stay.propertyId()).isEqualTo(expectedProperty);
                assertThat(stay.roomTypeId()).isEqualTo(expectedRoomType);
                assertThat(stay.supplier()).isEqualTo(A);
            });
        }

        @Test
        @DisplayName("연박 예약 가능 수는 날짜별 최솟값이다")
        void takesMinimumAcrossNights() {
            StaySearchResult result = search(new StubAdapter(A, 50, batch ->
                    success(offer("A-10023", "DLX-TWN", 2, 3, 1, 5))));

            assertThat(result.stays()).singleElement()
                    .satisfies(stay -> assertThat(stay.availableRooms()).isEqualTo(1));
        }

        @Test
        @DisplayName("하루라도 재고가 0 이면 빼고, 뺀 사실을 남긴다")
        void excludesSoldOut() {
            StaySearchResult result = search(new StubAdapter(A, 50, batch -> success(
                    offer("A-10023", "DLX-TWN", 2, 3, 2, 5),
                    offer("A-10044", "STD-DBL", 2, 2, 0, 4))));

            assertThat(result.stays()).hasSize(1);
            assertThat(result.excludedSoldOut()).isEqualTo(1);
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("매핑에 없는 상품은 빼고, 재고 0 과 구분해 센다")
        void excludesUnmapped() {
            StaySearchResult result = search(new StubAdapter(A, 50, batch -> success(
                    offer("A-10023", "DLX-TWN", 2, 3, 2, 5),
                    offer("A-99999", "UNKNOWN", 2, 3, 2, 5))));

            assertThat(result.stays()).hasSize(1);
            assertThat(result.excludedUnmapped()).isEqualTo(1);
            assertThat(result.excludedSoldOut()).isZero();
        }

        @Test
        @DisplayName("숙소는 매핑에 있어도 객실 타입이 없으면 뺀다")
        void excludesUnmappedRoomType() {
            StaySearchResult result = search(new StubAdapter(A, 50, batch ->
                    success(offer("A-10023", "NO-SUCH-ROOM", 2, 3, 2, 5))));

            assertThat(result.stays()).isEmpty();
            assertThat(result.excludedUnmapped()).isEqualTo(1);
        }

        @Test
        @DisplayName("요청 인원을 수용하지 못하는 상품은 뺀다")
        void excludesOverCapacity() {
            SearchCriteria four = new SearchCriteria(CRITERIA.dates(), 3, 1);

            StaySearchResult result = new StaySearchService(
                    List.of(new StubAdapter(A, 50, batch ->
                            success(offer("A-10023", "DLX-TWN", 2, 3, 2, 5)))),
                    repository, new SearchProperties(4, Duration.ofSeconds(5)))
                    .search(four);

            assertThat(result.stays()).isEmpty();
            assertThat(result.excludedOverCapacity()).isEqualTo(1);
        }
    }

    // ── 부분 실패 ────────────────────────────────────────────────

    @Nested
    @DisplayName("부분 실패")
    class PartialFailure {

        @Test
        @DisplayName("한 공급사가 실패해도 나머지 결과로 응답한다")
        void survivesOneSupplierFailure() {
            StaySearchResult result = search(
                    new StubAdapter(A, 50, batch -> success(offer("A-10023", "DLX-TWN", 2, 3, 2, 5))),
                    new StubAdapter(B, 50, batch ->
                            SupplierFetchResult.failure(FailureReason.TIMEOUT, "ReadTimeout")));

            assertThat(result.stays()).hasSize(1);
            assertThat(result.partial()).isTrue();
            assertThat(result.suppliers()).filteredOn(status -> !status.succeeded())
                    .singleElement()
                    .satisfies(status -> {
                        assertThat(status.supplier()).isEqualTo(B);
                        assertThat(status.failure()).contains(FailureReason.TIMEOUT);
                    });
        }

        @Test
        @DisplayName("묶음 하나만 실패해도 그 공급사는 실패로 표시한다")
        void marksSupplierFailedWhenAnyBatchFails() {
            repository.register(A, List.of(property("A-3", "R")));
            // 한계 1 이라 묶음이 셋이다. 그중 하나만 실패시킨다.
            StubAdapter adapter = new StubAdapter(A, 1, batch -> batch.contains("A-10044")
                    ? SupplierFetchResult.failure(FailureReason.SUPPLIER_ERROR, "HTTP 503")
                    : success(offer(batch.getFirst(), roomTypeOf(batch.getFirst()), 2, 3, 2, 5)));

            StaySearchResult result = search(adapter);

            // 성공한 묶음의 결과는 살아 있고, 공급사 상태만 실패다.
            assertThat(result.stays()).hasSize(2);
            assertThat(result.partial()).isTrue();
            assertThat(result.suppliers()).singleElement()
                    .satisfies(status -> assertThat(status.failure())
                            .contains(FailureReason.SUPPLIER_ERROR));
        }

        @Test
        @DisplayName("전부 실패하면 빈 결과지만 이유가 남는다")
        void reportsReasonWhenEverythingFails() {
            StaySearchResult result = search(
                    new StubAdapter(A, 50, batch ->
                            SupplierFetchResult.failure(FailureReason.SUPPLIER_ERROR, "HTTP 503")),
                    new StubAdapter(B, 50, batch ->
                            SupplierFetchResult.failure(FailureReason.TIMEOUT, "ReadTimeout")));

            assertThat(result.stays()).isEmpty();
            assertThat(result.excludedSoldOut()).isZero();
            assertThat(result.partial()).isTrue();
            assertThat(result.suppliers()).hasSize(2);
        }
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private StaySearchResult search(SupplierAdapter... adapters) {
        return new StaySearchService(
                List.of(adapters), repository, new SearchProperties(4, Duration.ofSeconds(5)))
                .search(CRITERIA);
    }

    private static String roomTypeOf(String propertyCode) {
        return switch (propertyCode) {
            case "A-10023" -> "DLX-TWN";
            case "A-10044" -> "STD-DBL";
            default -> "R";
        };
    }

    private static SupplierProperty property(String code, String roomTypeCode) {
        return new SupplierProperty(code, code + " Hotel",
                List.of(new SupplierRoomType(roomTypeCode, roomTypeCode + " Room", 2)));
    }

    /** 3박치 재고를 날짜별로 받는다. 요금은 판정에 쓰이지 않으므로 고정값이다. */
    private static SupplierOffer offer(
            String propertyCode, String roomTypeCode, int maxOccupancy, int... remaining) {
        List<DailyInventory> inventories = List.of(
                new DailyInventory(DAY1, remaining[0]),
                new DailyInventory(DAY2, remaining[1]),
                new DailyInventory(DAY3, remaining[2]));
        return new SupplierOffer(
                propertyCode, propertyCode + " Hotel",
                roomTypeCode, roomTypeCode + " Room",
                maxOccupancy, false,
                StayPrice.fromTotal(Money.of(300_000, "KRW")),
                inventories);
    }

    private static SupplierFetchResult<List<SupplierOffer>> success(SupplierOffer... offers) {
        return SupplierFetchResult.success(List.of(offers));
    }

    /** 받은 묶음을 기록한다. 나누는 일이 제대로 되는지는 호출된 모양으로만 확인할 수 있다. */
    private static final class StubAdapter implements SupplierAdapter {

        private final SupplierCode supplier;
        private final int maxBatchSize;
        private final Function<List<String>, SupplierFetchResult<List<SupplierOffer>>> responder;
        private final List<List<String>> receivedBatches = new ArrayList<>();

        private StubAdapter(
                SupplierCode supplier,
                int maxBatchSize,
                Function<List<String>, SupplierFetchResult<List<SupplierOffer>>> responder) {
            this.supplier = supplier;
            this.maxBatchSize = maxBatchSize;
            this.responder = responder;
        }

        @Override
        public SupplierCode supplier() {
            return supplier;
        }

        @Override
        public int maxBatchSize() {
            return maxBatchSize;
        }

        @Override
        public Mono<SupplierFetchResult<List<SupplierProperty>>> fetchProperties() {
            return Mono.just(SupplierFetchResult.success(List.of()));
        }

        @Override
        public Mono<SupplierFetchResult<List<SupplierOffer>>> fetchOffers(
                List<String> propertyCodes, SearchCriteria criteria) {
            synchronized (receivedBatches) {
                receivedBatches.add(List.copyOf(propertyCodes));
            }
            return Mono.fromSupplier(() -> responder.apply(propertyCodes));
        }

        private List<List<String>> receivedBatches() {
            synchronized (receivedBatches) {
                return List.copyOf(receivedBatches);
            }
        }
    }
}
