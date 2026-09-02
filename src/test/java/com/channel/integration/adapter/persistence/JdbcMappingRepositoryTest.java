package com.channel.integration.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

/**
 * 실제 H2 에 {@code schema.sql} 을 올리고 검증한다.
 *
 * <p>여기서 확인하려는 것은 SQL 이 도는지가 아니라 <b>내부 식별자가 안정적인지</b>다. 같은
 * 공급사 상품이 언제 조회해도 같은 식별자로 돌아온다는 것이 매핑 저장의 존재 이유이고,
 * 그건 실제 DB 에 두 번 써 봐야 확인된다.
 */
@JdbcTest
@Import(JdbcMappingRepository.class)
class JdbcMappingRepositoryTest {

    @TestConfiguration
    static class JdbcClientForTest {
        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }
    }

    private static final SupplierCode A = SupplierCode.of("A");
    private static final SupplierCode B = SupplierCode.of("B");

    @Autowired
    private JdbcMappingRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private static SupplierProperty property(String code, String... roomTypeCodes) {
        List<SupplierRoomType> roomTypes = List.of(roomTypeCodes).stream()
                .map(roomTypeCode -> new SupplierRoomType(roomTypeCode, roomTypeCode + " Room", 2))
                .toList();
        return new SupplierProperty(code, code + " Hotel", roomTypes);
    }

    // ── 식별자 안정성 ────────────────────────────────────────────

    @Test
    @DisplayName("같은 목록을 다시 동기화해도 내부 식별자가 바뀌지 않는다")
    void keepsInternalIdsAcrossSyncs() {
        List<SupplierProperty> properties = List.of(property("A-10023", "DLX-TWN"));

        repository.register(A, properties);
        MappingSnapshot first = repository.load();

        repository.register(A, properties);
        MappingSnapshot second = repository.load();

        assertThat(second.propertyId(A, "A-10023"))
                .isEqualTo(first.propertyId(A, "A-10023"));
        assertThat(second.roomTypeId(A, "A-10023", "DLX-TWN"))
                .isEqualTo(first.roomTypeId(A, "A-10023", "DLX-TWN"));
        assertThat(second.propertyCount()).isEqualTo(1);
        assertThat(second.roomTypeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 숙소가 늘어도 기존 식별자는 그대로다")
    void addsNewWithoutDisturbingExisting() {
        repository.register(A, List.of(property("A-10023", "DLX-TWN")));
        long existing = repository.load().propertyId(A, "A-10023").orElseThrow();

        repository.register(A, List.of(property("A-10023", "DLX-TWN"), property("A-10044", "STD-DBL")));
        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.propertyId(A, "A-10023")).hasValue(existing);
        assertThat(snapshot.propertyId(A, "A-10044")).isNotEmpty();
        assertThat(snapshot.propertyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("공급사가 다르면 코드가 같아도 다른 식별자를 받는다")
    void separatesSuppliersWithSameCode() {
        repository.register(A, List.of(property("SAME-CODE", "R1")));
        repository.register(B, List.of(property("SAME-CODE", "R1")));

        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.propertyId(A, "SAME-CODE"))
                .isNotEqualTo(snapshot.propertyId(B, "SAME-CODE"));
    }

    @Test
    @DisplayName("숙소가 다르면 객실 타입 코드가 같아도 다른 식별자를 받는다")
    void separatesRoomTypesByProperty() {
        // 객실 타입 코드는 숙소 안에서만 유일하다. 키에 숙소 코드가 들어가는 이유가 이것이다.
        repository.register(A, List.of(property("A-10023", "STD"), property("A-10044", "STD")));

        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.roomTypeId(A, "A-10023", "STD"))
                .isNotEqualTo(snapshot.roomTypeId(A, "A-10044", "STD"));
        assertThat(snapshot.roomTypeCount()).isEqualTo(2);
    }

    // ── 제약 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 키를 직접 두 번 넣으면 unique 제약이 막는다")
    void uniqueConstraintExists() {
        // 식별자 안정성을 보장하는 것은 upsert 문장이 아니라 이 제약이다. 실재하는지 확인한다.
        String insert = "insert into property_mapping (supplier, supplier_property_code) values (?, ?)";
        jdbc.sql(insert).params("A", "A-10023").update();

        assertThatThrownBy(() -> jdbc.sql(insert).params("A", "A-10023").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 조회 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("공급사별로 물어볼 숙소 코드를 준다")
    void groupsCodesBySupplier() {
        repository.register(A, List.of(property("A-10023", "DLX-TWN"), property("A-10044", "STD-DBL")));
        repository.register(B, List.of(property("B77120", "R-401")));

        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.suppliers()).containsExactlyInAnyOrder(A, B);
        assertThat(snapshot.propertyCodesOf(A)).containsExactly("A-10023", "A-10044");
        assertThat(snapshot.propertyCodesOf(B)).containsExactly("B77120");
    }

    @Test
    @DisplayName("매핑에 없는 코드는 빈 값으로 답한다")
    void reportsUnknownCodesAsEmpty() {
        repository.register(A, List.of(property("A-10023", "DLX-TWN")));

        MappingSnapshot snapshot = repository.load();

        assertThat(snapshot.propertyId(A, "A-99999")).isEmpty();
        assertThat(snapshot.roomTypeId(A, "A-10023", "NO-SUCH")).isEmpty();
        assertThat(snapshot.propertyCodesOf(SupplierCode.of("Z"))).isEmpty();
    }

    @Test
    @DisplayName("빈 목록은 아무것도 바꾸지 않는다")
    void ignoresEmptyList() {
        repository.register(A, List.of(property("A-10023", "DLX-TWN")));

        repository.register(A, List.of());

        assertThat(repository.load().propertyCount()).isEqualTo(1);
    }
}
