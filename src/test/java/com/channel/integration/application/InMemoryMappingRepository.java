package com.channel.integration.application;

import java.util.ArrayList;
import java.util.List;

import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.PropertyMapping;
import com.channel.integration.domain.RoomTypeMapping;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.MappingRepository;
import com.channel.integration.port.SupplierProperty;
import com.channel.integration.port.SupplierRoomType;

/**
 * 저장소의 계약만 흉내낸다 — 있으면 두고 없으면 넣는다.
 *
 * <p>SQL 이 실제로 도는지는 {@code JdbcMappingRepositoryTest} 가 진짜 H2 로 확인한다. 여기서는
 * 그 위에서 도는 로직만 보면 되므로 DB 를 띄우지 않는다.
 */
final class InMemoryMappingRepository implements MappingRepository {

    private final List<PropertyMapping> properties = new ArrayList<>();
    private final List<RoomTypeMapping> roomTypes = new ArrayList<>();
    private long sequence = 0;
    private int registerCalls = 0;

    @Override
    public void register(SupplierCode supplier, List<SupplierProperty> incoming) {
        registerCalls++;
        for (SupplierProperty property : incoming) {
            if (!hasProperty(supplier, property.propertyCode())) {
                properties.add(new PropertyMapping(++sequence, supplier, property.propertyCode()));
            }
            for (SupplierRoomType roomType : property.roomTypes()) {
                if (!hasRoomType(supplier, property.propertyCode(), roomType.roomTypeCode())) {
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

    int registerCalls() {
        return registerCalls;
    }

    private boolean hasProperty(SupplierCode supplier, String propertyCode) {
        return properties.stream().anyMatch(mapping ->
                mapping.supplier().equals(supplier) && mapping.propertyCode().equals(propertyCode));
    }

    private boolean hasRoomType(SupplierCode supplier, String propertyCode, String roomTypeCode) {
        return roomTypes.stream().anyMatch(mapping ->
                mapping.supplier().equals(supplier)
                        && mapping.propertyCode().equals(propertyCode)
                        && mapping.roomTypeCode().equals(roomTypeCode));
    }
}
