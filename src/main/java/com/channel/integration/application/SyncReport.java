package com.channel.integration.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;

/**
 * 한 번의 동기화 결과. 공급사별로 따로 담는다.
 *
 * <p>한 공급사가 실패해도 나머지는 반영되므로, 결과는 "성공 아니면 실패"가 아니라 <b>공급사별
 * 목록</b>이어야 한다. 검색의 부분 실패와 같은 생각이다.
 */
public record SyncReport(List<SupplierSync> suppliers) {

    public SyncReport {
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
    }

    /** 하나라도 실패했는가. 기동을 막지는 않지만 로그와 응답에는 드러나야 한다. */
    public boolean partial() {
        return suppliers.stream().anyMatch(supplier -> !supplier.succeeded());
    }

    public int syncedProperties() {
        return suppliers.stream().mapToInt(SupplierSync::properties).sum();
    }

    public int syncedRoomTypes() {
        return suppliers.stream().mapToInt(SupplierSync::roomTypes).sum();
    }

    /** 공급사 하나의 동기화 결과. 실패면 {@code failureReason} 이 차 있고 건수는 0 이다. */
    public record SupplierSync(
            SupplierCode supplier,
            int properties,
            int roomTypes,
            FailureReason failureReason,
            String detail) {

        public SupplierSync {
            Objects.requireNonNull(supplier, "supplier");
            detail = detail == null ? "" : detail;
        }

        public static SupplierSync synced(SupplierCode supplier, int properties, int roomTypes) {
            return new SupplierSync(supplier, properties, roomTypes, null, "");
        }

        public static SupplierSync failed(SupplierCode supplier, FailureReason reason, String detail) {
            return new SupplierSync(supplier, 0, 0, Objects.requireNonNull(reason, "reason"), detail);
        }

        public boolean succeeded() {
            return failureReason == null;
        }

        public Optional<FailureReason> failure() {
            return Optional.ofNullable(failureReason);
        }
    }
}
