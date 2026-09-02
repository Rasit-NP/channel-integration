package com.channel.integration.application;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.MappingRepository;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierProperty;

import reactor.core.publisher.Flux;

/**
 * 공급사 숙소 목록을 읽어 매핑을 채운다.
 *
 * <p>숙소 목록은 정적 콘텐츠라 검색할 때마다 부르지 않는다. 미리 받아 매핑으로 저장해 두고,
 * 검색은 그 매핑에서 "무엇을 물어볼지"를 꺼낸다. 언제 부를지는 {@link PropertySyncSchedule} 이
 * 정한다 — 이 클래스는 <b>어떻게</b>만 안다.
 *
 * <p>공급사 하나가 실패해도 나머지는 반영한다. 실패한 공급사의 기존 매핑은 그대로 둔다. 목록을
 * 못 받은 것과 그 공급사가 숙소를 하나도 취급하지 않게 된 것은 다른 사건이고, 응답만으로는
 * 구분되지 않는다. <b>모르는 것을 지웠다고 해석하지 않는다.</b>
 */
@Service
public class PropertySyncService {

    private static final Logger log = LoggerFactory.getLogger(PropertySyncService.class);

    private final List<SupplierAdapter> adapters;
    private final MappingRepository repository;

    PropertySyncService(List<SupplierAdapter> adapters, MappingRepository repository) {
        this.adapters = List.copyOf(adapters);
        this.repository = repository;
    }

    /**
     * 등록된 공급사 전체를 동기화한다.
     *
     * <p>호출은 병렬로 하고 저장은 순차로 한다. <b>JDBC 는 블로킹이라 리액터 스레드에서 부르면
     * 안 되기 때문</b>이다. 그래서 응답을 다 모아 블로킹으로 넘어온 뒤, 호출한 스레드에서
     * 저장한다. 블로킹은 여기 한 번뿐이고, 이 메서드는 요청 스레드가 아니라 기동·스케줄러·수동
     * 트리거에서만 불린다.
     */
    public SyncReport synchronize() {
        if (adapters.isEmpty()) {
            return new SyncReport(List.of());
        }

        List<Fetched> fetched = Flux.fromIterable(adapters)
                .flatMap(adapter -> adapter.fetchProperties()
                        .map(result -> new Fetched(adapter.supplier(), result)))
                .collectList()
                .blockOptional()
                .orElseGet(List::of);

        List<SyncReport.SupplierSync> results = new ArrayList<>();
        for (Fetched each : fetched) {
            results.add(apply(each));
        }

        SyncReport report = new SyncReport(results);
        log.info("숙소 목록 동기화 완료: 숙소={} 객실타입={} 부분실패={}",
                report.syncedProperties(), report.syncedRoomTypes(), report.partial());
        return report;
    }

    private SyncReport.SupplierSync apply(Fetched fetched) {
        SupplierCode supplier = fetched.supplier();
        return switch (fetched.result()) {
            case SupplierFetchResult.Success<List<SupplierProperty>> success -> {
                List<SupplierProperty> properties = success.value();
                repository.register(supplier, properties);
                yield SyncReport.SupplierSync.synced(
                        supplier, properties.size(), countRoomTypes(properties));
            }
            case SupplierFetchResult.Failure<List<SupplierProperty>> failure -> {
                // 기존 매핑은 건드리지 않는다. 목록을 못 받았을 뿐이다.
                log.warn("공급사 {} 숙소 목록 동기화 실패: reason={} detail={}. 기존 매핑을 유지한다",
                        supplier, failure.reason(), failure.detail());
                yield SyncReport.SupplierSync.failed(supplier, failure.reason(), failure.detail());
            }
        };
    }

    private static int countRoomTypes(List<SupplierProperty> properties) {
        return properties.stream().mapToInt(property -> property.roomTypes().size()).sum();
    }

    private record Fetched(SupplierCode supplier, SupplierFetchResult<List<SupplierProperty>> result) {
    }
}
