package com.channel.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.PropertyMapping;
import com.channel.integration.domain.RoomTypeMapping;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.MappingRepository;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

import reactor.core.publisher.Mono;

/**
 * 동기화가 <b>부분 실패를 어떻게 다루는지</b>를 본다.
 *
 * <p>확인할 것은 세 가지다 — 실패한 공급사가 성공한 공급사를 막지 않는가, 실패한 공급사의 기존
 * 매핑을 지우지 않는가, 실패 사실이 결과에 남는가. 저장 자체가 도는지는
 * {@code JdbcMappingRepositoryTest} 가 실제 DB 로 본다.
 */
class PropertySyncServiceTest {

    private static final SupplierCode A = SupplierCode.of("A");
    private static final SupplierCode B = SupplierCode.of("B");

    private final InMemoryMappingRepository repository = new InMemoryMappingRepository();

    // ── 부분 실패 ────────────────────────────────────────────────

    @Test
    @DisplayName("한 공급사가 실패해도 나머지는 반영한다")
    void appliesSurvivorsWhenOneSupplierFails() {
        PropertySyncService service = service(
                succeeding(A, property("A-10023", "DLX-TWN"), property("A-10044", "STD-DBL")),
                failing(B, FailureReason.TIMEOUT));

        SyncReport report = service.synchronize();
        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.propertyCodesOf(A)).containsExactlyInAnyOrder("A-10023", "A-10044");
        assertThat(snapshot.propertyCodesOf(B)).isEmpty();
        assertThat(report.partial()).isTrue();
        assertThat(report.syncedProperties()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패한 공급사의 실패 사유가 결과에 남는다")
    void keepsFailureReasonInReport() {
        PropertySyncService service = service(
                succeeding(A, property("A-10023", "DLX-TWN")),
                failing(B, FailureReason.SUPPLIER_ERROR));

        SyncReport report = service.synchronize();

        assertThat(report.suppliers()).hasSize(2);
        assertThat(report.suppliers()).filteredOn(SyncReport.SupplierSync::succeeded)
                .singleElement()
                .satisfies(sync -> assertThat(sync.supplier()).isEqualTo(A));
        assertThat(report.suppliers()).filteredOn(sync -> !sync.succeeded())
                .singleElement()
                .satisfies(sync -> {
                    assertThat(sync.supplier()).isEqualTo(B);
                    assertThat(sync.failure()).contains(FailureReason.SUPPLIER_ERROR);
                    assertThat(sync.properties()).isZero();
                });
    }

    @Test
    @DisplayName("실패한 공급사의 기존 매핑은 지우지 않는다")
    void keepsExistingMappingsOfFailedSupplier() {
        // 목록을 못 받은 것과 그 공급사가 숙소를 접은 것은 다른 사건이다. 응답으로는 구분되지 않는다.
        service(succeeding(A, property("A-10023", "DLX-TWN")),
                succeeding(B, property("B77120", "R-401")))
                .synchronize();
        long existingId = repository.load().propertyId(B, "B77120").orElseThrow();

        service(succeeding(A, property("A-10023", "DLX-TWN")),
                failing(B, FailureReason.TIMEOUT))
                .synchronize();

        MappingSnapshot snapshot = repository.load();
        assertThat(snapshot.propertyId(B, "B77120")).hasValue(existingId);
        assertThat(snapshot.roomTypeId(B, "B77120", "R-401")).isNotEmpty();
    }

    @Test
    @DisplayName("실패한 공급사에는 저장을 시도하지 않는다")
    void doesNotWriteForFailedSupplier() {
        service(failing(A, FailureReason.TIMEOUT), failing(B, FailureReason.UNAUTHORIZED))
                .synchronize();

        assertThat(repository.registerCalls).isZero();
        assertThat(repository.load().isEmpty()).isTrue();
    }

    // ── 반복 실행 ────────────────────────────────────────────────

    @Test
    @DisplayName("같은 목록으로 다시 돌려도 내부 식별자가 바뀌지 않는다")
    void isIdempotent() {
        PropertySyncService service = service(succeeding(A, property("A-10023", "DLX-TWN")));

        service.synchronize();
        long first = repository.load().propertyId(A, "A-10023").orElseThrow();
        service.synchronize();

        assertThat(repository.load().propertyId(A, "A-10023")).hasValue(first);
        assertThat(repository.load().propertyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("등록된 공급사가 없으면 빈 결과를 준다")
    void handlesNoAdapters() {
        SyncReport report = service().synchronize();

        assertThat(report.suppliers()).isEmpty();
        assertThat(report.partial()).isFalse();
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private PropertySyncService service(SupplierAdapter... adapters) {
        return new PropertySyncService(List.of(adapters), repository);
    }

    private static SupplierProperty property(String code, String... roomTypeCodes) {
        List<SupplierRoomType> roomTypes = List.of(roomTypeCodes).stream()
                .map(roomTypeCode -> new SupplierRoomType(roomTypeCode, roomTypeCode + " Room", 2))
                .toList();
        return new SupplierProperty(code, code + " Hotel", roomTypes);
    }

    private static SupplierAdapter succeeding(SupplierCode supplier, SupplierProperty... properties) {
        return new StubAdapter(supplier, SupplierFetchResult.success(List.of(properties)));
    }

    private static SupplierAdapter failing(SupplierCode supplier, FailureReason reason) {
        return new StubAdapter(supplier, SupplierFetchResult.failure(reason, "stub"));
    }

    private record StubAdapter(
            SupplierCode supplier, SupplierFetchResult<List<SupplierProperty>> properties)
            implements SupplierAdapter {

        @Override
        public int maxBatchSize() {
            return 50;
        }

        @Override
        public Mono<SupplierFetchResult<List<SupplierProperty>>> fetchProperties() {
            return Mono.just(properties);
        }

        @Override
        public Mono<SupplierFetchResult<List<SupplierOffer>>> fetchOffers(
                List<String> propertyCodes, SearchCriteria criteria) {
            return Mono.just(SupplierFetchResult.success(List.of()));
        }
    }

    /** 저장소의 계약만 흉내낸다 — 있으면 두고 없으면 넣는다. SQL 검증은 여기서 하지 않는다. */
    private static final class InMemoryMappingRepository implements MappingRepository {

        private final List<PropertyMapping> properties = new ArrayList<>();
        private final List<RoomTypeMapping> roomTypes = new ArrayList<>();
        private long sequence = 0;
        private int registerCalls = 0;

        @Override
        public void register(SupplierCode supplier, List<SupplierProperty> incoming) {
            registerCalls++;
            for (SupplierProperty property : incoming) {
                boolean known = properties.stream().anyMatch(mapping ->
                        mapping.supplier().equals(supplier)
                                && mapping.propertyCode().equals(property.propertyCode()));
                if (!known) {
                    properties.add(new PropertyMapping(++sequence, supplier, property.propertyCode()));
                }
                for (SupplierRoomType roomType : property.roomTypes()) {
                    boolean knownRoomType = roomTypes.stream().anyMatch(mapping ->
                            mapping.supplier().equals(supplier)
                                    && mapping.propertyCode().equals(property.propertyCode())
                                    && mapping.roomTypeCode().equals(roomType.roomTypeCode()));
                    if (!knownRoomType) {
                        roomTypes.add(new RoomTypeMapping(
                                ++sequence, supplier, property.propertyCode(), roomType.roomTypeCode()));
                    }
                }
            }
        }

        @Override
        public MappingSnapshot load() {
            return new MappingSnapshot(List.copyOf(properties), List.copyOf(roomTypes));
        }
    }
}
