package com.channel.integration.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.channel.integration.domain.Stay;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;

/**
 * 통합 검색 결과.
 *
 * <p>결과 목록만으로는 <b>비어 있는 이유</b>를 알 수 없다. 조회는 다 됐는데 전부 만실인 것과,
 * 공급사를 못 봐서 비어 있는 것과, 우리 매핑이 모자라 버린 것은 서로 다른 사건이고 클라이언트가
 * 할 일도 다르다. 그래서 제외 사유를 종류별로 세어 함께 돌려준다.
 *
 * @param excludedSoldOut       요청 기간 중 하루라도 재고가 0 이라 뺀 상품 수
 * @param excludedUnmapped      공급사가 줬지만 우리 매핑에 없어 내부 식별자를 줄 수 없던 상품 수
 * @param excludedOverCapacity  요청 인원을 수용하지 못해 뺀 상품 수
 */
public record StaySearchResult(
        List<Stay> stays,
        List<SupplierStatus> suppliers,
        int excludedSoldOut,
        int excludedUnmapped,
        int excludedOverCapacity) {

    public StaySearchResult {
        stays = stays == null ? List.of() : List.copyOf(stays);
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
    }

    public static StaySearchResult empty() {
        return new StaySearchResult(List.of(), List.of(), 0, 0, 0);
    }

    /**
     * 일부 공급사를 못 봤는가. 부분 실패는 오류가 아니라 <b>불완전한 성공</b>이므로 응답은
     * 200 이고, 완전성 판단은 클라이언트에게 맡긴다.
     */
    public boolean partial() {
        return suppliers.stream().anyMatch(supplier -> !supplier.succeeded());
    }

    /** 조회를 시도한 공급사 하나의 상태. 물어볼 숙소가 없어 호출하지 않은 공급사는 여기 없다. */
    public record SupplierStatus(SupplierCode supplier, FailureReason failureReason, String detail) {

        public SupplierStatus {
            Objects.requireNonNull(supplier, "supplier");
            detail = detail == null ? "" : detail;
        }

        public static SupplierStatus ok(SupplierCode supplier) {
            return new SupplierStatus(supplier, null, "");
        }

        public static SupplierStatus failed(SupplierCode supplier, FailureReason reason, String detail) {
            return new SupplierStatus(supplier, Objects.requireNonNull(reason, "reason"), detail);
        }

        public boolean succeeded() {
            return failureReason == null;
        }

        public Optional<FailureReason> failure() {
            return Optional.ofNullable(failureReason);
        }
    }
}
