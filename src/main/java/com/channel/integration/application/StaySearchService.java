package com.channel.integration.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.channel.integration.domain.Availability;
import com.channel.integration.domain.MappingSnapshot;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.Stay;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;
import com.channel.integration.port.MappingRepository;
import com.channel.integration.port.SupplierAdapter;
import com.channel.integration.port.SupplierFetchResult;
import com.channel.integration.port.SupplierOffer;

import reactor.core.publisher.Flux;

/**
 * 통합 검색. 여러 공급사를 동시에 조회하고, 정규화·병합해 하나의 결과로 만든다.
 *
 * <p>이 클래스가 하는 일은 넷이다.
 * <ol>
 *   <li>매핑에서 <b>무엇을 물어볼지</b> 꺼내 공급사별 묶음으로 나눈다</li>
 *   <li>묶음을 병렬로 호출하고, 실패한 묶음은 실패로 따로 센다</li>
 *   <li>공급사 코드를 <b>내부 식별자로 확정</b>하고 재고를 판정한다</li>
 *   <li>공급사별 상태와 제외 건수를 붙여 돌려준다</li>
 * </ol>
 *
 * <p>어느 공급사인지는 알지 못한다. 등록된 {@link SupplierAdapter} 목록을 순회할 뿐이라, 공급사가
 * 늘어도 이 클래스는 고치지 않는다.
 */
@Service
public class StaySearchService {

    private static final Logger log = LoggerFactory.getLogger(StaySearchService.class);

    private final Map<SupplierCode, SupplierAdapter> adapters;
    private final MappingRepository repository;
    private final SearchProperties properties;

    StaySearchService(
            List<SupplierAdapter> adapters,
            MappingRepository repository,
            SearchProperties properties) {
        Map<SupplierCode, SupplierAdapter> byCode = new LinkedHashMap<>();
        for (SupplierAdapter adapter : adapters) {
            byCode.put(adapter.supplier(), adapter);
        }
        this.adapters = Map.copyOf(byCode);
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 블로킹이다. 이 메서드를 부르는 컨트롤러가 그 블로킹을 감당하는 바깥 경계다.
     *
     * <p>전체 상한을 넘기면 <b>그때까지 도착한 묶음만으로</b> 결과를 만든다. 못 받은 공급사는
     * 실패로 표시된다. 한 공급사가 늦다고 아무것도 못 주는 것보다 낫다.
     */
    public StaySearchResult search(SearchCriteria criteria) {
        MappingSnapshot snapshot = repository.load();
        List<SupplierPlan> plans = plan(snapshot);
        if (plans.isEmpty()) {
            // 물어볼 숙소가 없다. 공급사를 부르지 않는 것이 맞고, 이것은 실패가 아니다.
            log.info("검색 대상 매핑이 없다. 동기화가 아직 돌지 않았을 수 있다");
            return StaySearchResult.empty();
        }

        List<BatchOutcome> outcomes = Flux.fromIterable(plans)
                .flatMap(plan -> call(plan, criteria))
                .take(properties.timeout())
                .collectList()
                .blockOptional()
                .orElseGet(List::of);

        return assemble(criteria, snapshot, plans, outcomes);
    }

    // ── 무엇을 물어볼지 ──────────────────────────────────────────

    /** 매핑이 있는 공급사만, 그 공급사가 감당하는 크기로 나눠 계획을 세운다. */
    private List<SupplierPlan> plan(MappingSnapshot snapshot) {
        List<SupplierPlan> plans = new ArrayList<>();
        for (SupplierCode supplier : snapshot.suppliers()) {
            SupplierAdapter adapter = adapters.get(supplier);
            if (adapter == null) {
                // 매핑은 있는데 어댑터가 없다. 그 공급사 연동이 빠졌다는 뜻이라 알아야 한다.
                log.warn("매핑에 있는 공급사 {} 의 어댑터가 등록되어 있지 않다. 건너뛴다", supplier);
                continue;
            }
            List<String> codes = snapshot.propertyCodesOf(supplier);
            if (codes.isEmpty()) {
                continue;
            }
            plans.add(new SupplierPlan(adapter, partition(codes, adapter.maxBatchSize())));
        }
        return List.copyOf(plans);
    }

    /** 묶는 것은 공급사의 제약이고, 나누는 일은 여기서 한다. 한계값은 어댑터가 선언한다. */
    private static List<List<String>> partition(List<String> codes, int size) {
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < codes.size(); start += size) {
            batches.add(List.copyOf(codes.subList(start, Math.min(start + size, codes.size()))));
        }
        return List.copyOf(batches);
    }

    // ── 호출 ─────────────────────────────────────────────────────

    /**
     * 한 공급사의 묶음들을 병렬로 부른다. 동시 호출 수에 상한을 두는 이유는, 상한 없이 띄우면
     * 숙소가 늘어날수록 우리가 그 공급사를 두드리는 꼴이 되기 때문이다.
     */
    private Flux<BatchOutcome> call(SupplierPlan plan, SearchCriteria criteria) {
        SupplierCode supplier = plan.adapter().supplier();
        return Flux.fromIterable(plan.batches())
                .flatMap(batch -> plan.adapter().fetchOffers(batch, criteria)
                                .map(result -> new BatchOutcome(supplier, result)),
                        properties.maxConcurrency());
    }

    // ── 정규화와 병합 ────────────────────────────────────────────

    private StaySearchResult assemble(
            SearchCriteria criteria,
            MappingSnapshot snapshot,
            List<SupplierPlan> plans,
            List<BatchOutcome> outcomes) {

        List<Stay> stays = new ArrayList<>();
        Map<SupplierCode, StaySearchResult.SupplierStatus> statuses = new LinkedHashMap<>();
        Map<SupplierCode, Integer> received = new LinkedHashMap<>();
        int soldOut = 0;
        int unmapped = 0;
        int overCapacity = 0;

        for (BatchOutcome outcome : outcomes) {
            SupplierCode supplier = outcome.supplier();
            received.merge(supplier, 1, Integer::sum);

            switch (outcome.result()) {
                case SupplierFetchResult.Success<List<SupplierOffer>> success -> {
                    statuses.putIfAbsent(supplier, StaySearchResult.SupplierStatus.ok(supplier));
                    for (SupplierOffer offer : success.value()) {
                        switch (resolve(criteria, snapshot, supplier, offer)) {
                            case Resolution.Included(Stay stay) -> stays.add(stay);
                            case Resolution.Unmapped ignored -> unmapped++;
                            case Resolution.OverCapacity ignored -> overCapacity++;
                            case Resolution.SoldOut ignored -> soldOut++;
                        }
                    }
                }
                case SupplierFetchResult.Failure<List<SupplierOffer>> failure ->
                    // 묶음 하나만 실패해도 그 공급사 결과는 불완전하다. 실패가 성공을 덮어쓴다.
                    statuses.put(supplier, StaySearchResult.SupplierStatus.failed(
                            supplier, failure.reason(), failure.detail()));
            }
        }

        markMissingAsTimedOut(plans, received, statuses);

        return new StaySearchResult(
                stays, List.copyOf(statuses.values()), soldOut, unmapped, overCapacity);
    }

    /**
     * 전체 상한에 걸려 아직 오지 않은 묶음이 있으면, 그 공급사를 타임아웃으로 표시한다.
     *
     * <p>내부적으로는 (공급사 × 묶음) 단위로 실패를 세지만, 밖에는 공급사 단위로만 노출한다.
     * 묶음은 우리 사정이지 고객이 알 일이 아니다.
     */
    private void markMissingAsTimedOut(
            List<SupplierPlan> plans,
            Map<SupplierCode, Integer> received,
            Map<SupplierCode, StaySearchResult.SupplierStatus> statuses) {

        for (SupplierPlan plan : plans) {
            SupplierCode supplier = plan.adapter().supplier();
            int expected = plan.batches().size();
            int actual = received.getOrDefault(supplier, 0);
            if (actual < expected) {
                log.warn("공급사 {} 묶음 {}개 중 {}개만 도착했다. 검색 상한을 넘겼다",
                        supplier, expected, actual);
                statuses.put(supplier, StaySearchResult.SupplierStatus.failed(
                        supplier, FailureReason.TIMEOUT,
                        "%d/%d 묶음 미도착".formatted(expected - actual, expected)));
            }
        }
    }

    /**
     * 공급사 응답 한 건을 검색 결과로 확정하거나, 뺄 이유를 붙여 돌려준다.
     *
     * <p>순서에 의미가 있다. 매핑을 못 찾으면 <b>무엇인지</b>를 모르는 것이라 그다음 판단이
     * 성립하지 않는다. 인원은 상품 자체의 성질이고, 재고는 요청 기간에 대한 판정이다.
     */
    private Resolution resolve(
            SearchCriteria criteria,
            MappingSnapshot snapshot,
            SupplierCode supplier,
            SupplierOffer offer) {

        OptionalLong propertyId = snapshot.propertyId(supplier, offer.propertyCode());
        OptionalLong roomTypeId =
                snapshot.roomTypeId(supplier, offer.propertyCode(), offer.roomTypeCode());
        if (propertyId.isEmpty() || roomTypeId.isEmpty()) {
            // 매핑에 없는 코드다. 내부 식별자를 지어낼 수는 없으므로 뺀다.
            log.debug("매핑에 없는 상품을 건너뛴다: supplier={} property={} roomType={}",
                    supplier, offer.propertyCode(), offer.roomTypeCode());
            return new Resolution.Unmapped();
        }

        if (offer.maxOccupancy() < criteria.totalGuests()) {
            // 공급사는 수용 가능한 것만 준다고 되어 있다. 그래도 확인한다 — 재고 날짜가 빠졌을 때
            // 0 으로 보는 것과 같은 기조다. 상대가 계약을 지킨다고 가정하지 않는다.
            return new Resolution.OverCapacity();
        }

        Availability availability = Availability.forStay(offer.inventories(), criteria.dates());
        if (!availability.bookable()) {
            return new Resolution.SoldOut();
        }

        return new Resolution.Included(new Stay(
                propertyId.getAsLong(),
                offer.propertyName(),
                roomTypeId.getAsLong(),
                offer.roomTypeName(),
                offer.maxOccupancy(),
                availability,
                supplier,
                offer.breakfastIncluded(),
                offer.price()));
    }

    // ── 내부 타입 ────────────────────────────────────────────────

    private record SupplierPlan(SupplierAdapter adapter, List<List<String>> batches) {
    }

    private record BatchOutcome(
            SupplierCode supplier, SupplierFetchResult<List<SupplierOffer>> result) {
    }

    /** 한 건을 담을지, 뺄지. 뺐다면 어떤 이유였는지. */
    private sealed interface Resolution {
        record Included(Stay stay) implements Resolution {
        }

        record Unmapped() implements Resolution {
        }

        record OverCapacity() implements Resolution {
        }

        record SoldOut() implements Resolution {
        }
    }
}
