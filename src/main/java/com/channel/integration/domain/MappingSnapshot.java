package com.channel.integration.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 한 시점의 매핑 전체를 담은 읽기 전용 사본.
 *
 * <p>검색 한 번에 매핑이 두 번 필요하다. 시작할 때는 <b>어느 공급사에 어떤 숙소 코드를
 * 물어볼지</b>를 알아야 하고, 응답을 받은 뒤에는 <b>그 코드가 우리 쪽 무엇인지</b>를 알아야
 * 한다. 두 번을 각각 DB 에 묻는 대신 한 번 읽어 두고 조회는 메모리에서 한다.
 *
 * <p>검색 도중 매핑이 바뀌어도 이 사본은 바뀌지 않는다. 한 번의 검색이 일관된 매핑 위에서
 * 끝나는 편이, 중간에 달라진 매핑으로 절반씩 해석하는 것보다 낫다.
 */
public final class MappingSnapshot {

    private final Map<SupplierCode, List<String>> propertyCodes;
    private final Map<PropertyKey, Long> propertyIds;
    private final Map<RoomTypeKey, Long> roomTypeIds;

    public MappingSnapshot(List<PropertyMapping> properties, List<RoomTypeMapping> roomTypes) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(roomTypes, "roomTypes");

        Map<SupplierCode, List<String>> codes = new LinkedHashMap<>();
        Map<PropertyKey, Long> propertyIds = new HashMap<>();
        for (PropertyMapping mapping : properties) {
            codes.computeIfAbsent(mapping.supplier(), supplier -> new ArrayList<>())
                    .add(mapping.propertyCode());
            propertyIds.put(
                    new PropertyKey(mapping.supplier(), mapping.propertyCode()), mapping.internalId());
        }
        codes.replaceAll((supplier, list) -> List.copyOf(list));

        Map<RoomTypeKey, Long> roomTypeIds = new HashMap<>();
        for (RoomTypeMapping mapping : roomTypes) {
            roomTypeIds.put(
                    new RoomTypeKey(mapping.supplier(), mapping.propertyCode(), mapping.roomTypeCode()),
                    mapping.internalId());
        }

        this.propertyCodes = Map.copyOf(codes);
        this.propertyIds = Map.copyOf(propertyIds);
        this.roomTypeIds = Map.copyOf(roomTypeIds);
    }

    public static MappingSnapshot empty() {
        return new MappingSnapshot(List.of(), List.of());
    }

    /** 매핑을 가진 공급사들. 아직 동기화되지 않은 공급사는 여기 없다. */
    public Set<SupplierCode> suppliers() {
        return propertyCodes.keySet();
    }

    /** 그 공급사에 물어볼 숙소 코드 전체. 검색이 이 목록을 묶음으로 나눈다. */
    public List<String> propertyCodesOf(SupplierCode supplier) {
        return propertyCodes.getOrDefault(supplier, List.of());
    }

    /** 없으면 빈 값이다. 매핑에 없는 코드를 공급사가 돌려준 경우이므로 내부 식별자를 줄 수 없다. */
    public OptionalLong propertyId(SupplierCode supplier, String propertyCode) {
        return toOptional(propertyIds.get(new PropertyKey(supplier, propertyCode)));
    }

    /** 없으면 빈 값이다. 숙소 코드까지 맞아야 찾힌다. */
    public OptionalLong roomTypeId(SupplierCode supplier, String propertyCode, String roomTypeCode) {
        return toOptional(roomTypeIds.get(new RoomTypeKey(supplier, propertyCode, roomTypeCode)));
    }

    /** 매핑이 하나도 없으면 물어볼 숙소가 없다는 뜻이다. */
    public boolean isEmpty() {
        return propertyIds.isEmpty();
    }

    public int propertyCount() {
        return propertyIds.size();
    }

    public int roomTypeCount() {
        return roomTypeIds.size();
    }

    private static OptionalLong toOptional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private record PropertyKey(SupplierCode supplier, String propertyCode) {
    }

    private record RoomTypeKey(SupplierCode supplier, String propertyCode, String roomTypeCode) {
    }
}
