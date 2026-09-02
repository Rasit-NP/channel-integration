package com.channel.integration.api;

import java.time.LocalDate;
import java.util.List;

import com.channel.integration.application.StaySearchResult;
import com.channel.integration.domain.NightlyRate;
import com.channel.integration.domain.Stay;
import com.channel.integration.domain.StayPrice;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 통합 검색 응답.
 *
 * <p>도메인 타입을 그대로 직렬화하지 않고 여기서 옮겨 담는다. 도메인이 JSON 표현을 신경 쓰기
 * 시작하면 응답 형식을 바꾸려고 도메인을 고치게 된다.
 *
 * <p>일부 공급사 조회가 실패해도 이 응답은 <b>200</b> 으로 나간다. 부분 실패는 오류가 아니라
 * 불완전한 성공이므로, 무엇을 못 봤는지를 담아 클라이언트가 완전성을 판단하게 한다.
 */
record StaySearchResponse(
        List<StayView> stays,
        List<SupplierView> suppliers,
        boolean partial,
        int excludedSoldOut,
        int excludedUnmapped,
        int excludedOverCapacity) {

    static StaySearchResponse from(StaySearchResult result) {
        return new StaySearchResponse(
                result.stays().stream().map(StayView::from).toList(),
                result.suppliers().stream().map(SupplierView::from).toList(),
                result.partial(),
                result.excludedSoldOut(),
                result.excludedUnmapped(),
                result.excludedOverCapacity());
    }

    /** 출처 공급사는 밝히되, 형태는 어느 공급사에서 왔든 같다. */
    record StayView(
            long propertyId,
            String propertyName,
            long roomTypeId,
            String roomTypeName,
            int maxOccupancy,
            int availableRooms,
            String supplier,
            boolean breakfastIncluded,
            PriceView price) {

        static StayView from(Stay stay) {
            return new StayView(
                    stay.propertyId(),
                    stay.propertyName(),
                    stay.roomTypeId(),
                    stay.roomTypeName(),
                    stay.maxOccupancy(),
                    stay.availableRooms(),
                    stay.supplier().value(),
                    stay.breakfastIncluded(),
                    PriceView.from(stay.price()));
        }
    }

    /**
     * 표준은 <b>기간 전체 총액, 세금 포함</b>이다. 세액과 날짜별 내역은 주는 공급사에서만 채워지며,
     * 없으면 필드 자체가 나가지 않는다. 빈 값을 0 이나 빈 배열로 내보내면 "0원"이나 "내역 없음"과
     * 구분되지 않는다.
     */
    record PriceView(
            long totalAmount,
            String currency,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long taxAmount,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<NightlyRateView> nightlyRates) {

        static PriceView from(StayPrice price) {
            return new PriceView(
                    price.totalAmount().amount(),
                    price.currency(),
                    price.tax().map(money -> money.amount()).orElse(null),
                    price.nightlyRates().stream().map(NightlyRateView::from).toList());
        }
    }

    /** {@code amount} 는 그날 고객이 실제로 내는 금액(세금 포함)이다. */
    record NightlyRateView(LocalDate date, long amount, long taxAmount) {

        static NightlyRateView from(NightlyRate rate) {
            return new NightlyRateView(
                    rate.date(), rate.grossAmount().amount(), rate.taxAmount().amount());
        }
    }

    record SupplierView(
            String supplier,
            String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

        static SupplierView from(StaySearchResult.SupplierStatus status) {
            return new SupplierView(
                    status.supplier().value(),
                    status.succeeded() ? "OK" : "FAILED",
                    status.failure().map(Enum::name).orElse(null));
        }
    }
}
