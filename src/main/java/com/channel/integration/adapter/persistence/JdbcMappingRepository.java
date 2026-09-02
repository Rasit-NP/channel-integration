package com.channel.integration.adapter.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.PropertyMapping;
import com.channel.integration.domain.RoomTypeMapping;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.MappingRepository;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

/**
 * 매핑 저장소의 H2 구현.
 *
 * <p>SQL 을 그대로 둔 것은 저장하는 것이 값 몇 개뿐이기 때문이다. 테이블 둘에 조인이 없고
 * 쓰기도 upsert 하나뿐이라, 매핑 계층을 얹어도 줄어드는 코드가 없다. 대신 스키마는
 * {@code schema.sql} 이 소유하고, 내부 식별자 안정성을 보장하는 unique 제약이 거기 눈으로
 * 보인다.
 */
@Repository
class JdbcMappingRepository implements MappingRepository {

    /**
     * 있으면 두고 없으면 넣는다. 지정한 키로 이미 있는 행은 갱신 대상이 되는데, 갱신할 컬럼이
     * 키 컬럼뿐이라 결과적으로 값이 바뀌지 않고 <b>{@code id} 도 그대로 남는다.</b> 그게 여기서
     * 필요한 성질이다.
     *
     * <p>{@code MERGE ... KEY} 는 H2 문법이다. 다른 DB 로 옮기면 이 두 문장을 그 DB 의 upsert
     * 로 바꿔야 한다. 바꿀 자리가 여기뿐이라는 것을 알고 쓴다.
     */
    private static final String MERGE_PROPERTY = """
            merge into property_mapping (supplier, supplier_property_code)
            key (supplier, supplier_property_code)
            values (:supplier, :propertyCode)
            """;

    private static final String MERGE_ROOM_TYPE = """
            merge into room_type_mapping (supplier, supplier_property_code, supplier_room_type_code)
            key (supplier, supplier_property_code, supplier_room_type_code)
            values (:supplier, :propertyCode, :roomTypeCode)
            """;

    private final JdbcClient jdbc;

    JdbcMappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 공급사의 목록 전체를 한 트랜잭션으로 반영한다. 절반만 반영된 매핑을 남기지 않기
     * 위해서다.
     *
     * <p>한 건씩 도는 이유는 지금 숙소가 몇 개뿐이기 때문이다. 목록이 수천 개로 늘면 묶어
     * 보내야 한다.
     */
    @Override
    @Transactional
    public void register(SupplierCode supplier, List<SupplierProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        for (SupplierProperty property : properties) {
            jdbc.sql(MERGE_PROPERTY)
                    .param("supplier", supplier.value())
                    .param("propertyCode", property.propertyCode())
                    .update();

            for (SupplierRoomType roomType : property.roomTypes()) {
                jdbc.sql(MERGE_ROOM_TYPE)
                        .param("supplier", supplier.value())
                        .param("propertyCode", property.propertyCode())
                        .param("roomTypeCode", roomType.roomTypeCode())
                        .update();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MappingSnapshot load() {
        List<PropertyMapping> properties = jdbc
                .sql("select id, supplier, supplier_property_code from property_mapping order by id")
                .query((rs, rowNum) -> new PropertyMapping(
                        rs.getLong("id"),
                        SupplierCode.of(rs.getString("supplier")),
                        rs.getString("supplier_property_code")))
                .list();

        List<RoomTypeMapping> roomTypes = jdbc
                .sql("""
                     select id, supplier, supplier_property_code, supplier_room_type_code
                     from room_type_mapping order by id
                     """)
                .query((rs, rowNum) -> new RoomTypeMapping(
                        rs.getLong("id"),
                        SupplierCode.of(rs.getString("supplier")),
                        rs.getString("supplier_property_code"),
                        rs.getString("supplier_room_type_code")))
                .list();

        return new MappingSnapshot(properties, roomTypes);
    }
}
