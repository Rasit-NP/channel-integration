package com.channel.integration.api;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.channel.integration.application.PropertySyncService;
import com.channel.integration.application.SyncReport;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 숙소 목록 동기화를 손으로 돌리는 통로.
 *
 * <p>기동 시 1회와 주기적 갱신만으로는 "공급사에 숙소가 추가됐는데 다음 주기까지 기다려야
 * 하는" 상황을 넘길 방법이 없다. 시연할 때도 필요하다 — Mock 을 띄우고 곧바로 매핑을 채울 수
 * 있어야 한다.
 *
 * <p>운영용 통로이므로 고객 API(`/api/v1/**`)와 경로를 나눴다. 인증은 지금 범위가 아니라
 * 걸지 않았다. 이대로 밖에 열어둘 통로는 아니다.
 */
@RestController
class SyncController {

    private final PropertySyncService service;

    SyncController(PropertySyncService service) {
        this.service = service;
    }

    /**
     * 동기화는 블로킹이다. 여기가 그 블로킹을 감당하는 바깥 경계다.
     *
     * <p>일부 공급사가 실패해도 200 을 준다. 나머지는 반영됐으므로 요청이 실패한 것이 아니다.
     * 대신 무엇이 실패했는지를 본문에 담아, 호출한 쪽이 결과의 완전성을 판단하게 한다.
     */
    @PostMapping("/internal/suppliers/sync")
    SyncResponse sync() {
        return SyncResponse.from(service.synchronize());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SyncResponse(
            List<SupplierEntry> suppliers, int properties, int roomTypes, boolean partial) {

        static SyncResponse from(SyncReport report) {
            List<SupplierEntry> entries = report.suppliers().stream()
                    .map(SupplierEntry::from)
                    .toList();
            return new SyncResponse(
                    entries, report.syncedProperties(), report.syncedRoomTypes(), report.partial());
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record SupplierEntry(
                String supplier, String status, int properties, int roomTypes, String reason) {

            static SupplierEntry from(SyncReport.SupplierSync sync) {
                return new SupplierEntry(
                        sync.supplier().value(),
                        sync.succeeded() ? "OK" : "FAILED",
                        sync.properties(),
                        sync.roomTypes(),
                        sync.failure().map(Enum::name).orElse(null));
            }
        }
    }
}
