package com.channel.integration.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.channel.integration.application.StaySearchResult;
import com.channel.integration.application.StaySearchResult.SupplierStatus;
import com.channel.integration.application.StaySearchService;
import com.channel.integration.domain.Availability;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.Money;
import com.channel.integration.domain.NightlyRate;
import com.channel.integration.domain.SearchCriteria;
import com.channel.integration.domain.Stay;
import com.channel.integration.domain.StayPrice;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;

/**
 * 응답 계약을 고정한다.
 *
 * <p>두 가지가 핵심이다 — 일부 공급사가 실패해도 <b>200</b> 이라는 것, 그리고 공급사가 주지 않은
 * 정보(세액·날짜별 내역)는 <b>0 이나 빈 배열이 아니라 아예 없다</b>는 것. 없는 것을 0 으로
 * 내보내면 "0원"과 구분되지 않는다.
 */
@WebMvcTest(StaySearchController.class)
class StaySearchControllerTest {

    private static final String URL = "/api/v1/stays/search";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StaySearchService service;

    // ── 응답 형태 ────────────────────────────────────────────────

    @Test
    @DisplayName("내부 식별자와 출처, 예약 가능 수를 담아 응답한다")
    void returnsNormalizedStays() throws Exception {
        given(service.search(any(SearchCriteria.class))).willReturn(new StaySearchResult(
                List.of(stayWithBreakdown()),
                List.of(SupplierStatus.ok(SupplierCode.of("A"))),
                0, 0, 0));

        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-01").param("checkOut", "2026-09-04")
                        .param("adults", "2").param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stays[0].propertyId").value(11))
                .andExpect(jsonPath("$.stays[0].propertyName").value("Riverside Hotel Seoul"))
                .andExpect(jsonPath("$.stays[0].roomTypeId").value(22))
                .andExpect(jsonPath("$.stays[0].maxOccupancy").value(2))
                .andExpect(jsonPath("$.stays[0].availableRooms").value(1))
                .andExpect(jsonPath("$.stays[0].supplier").value("A"))
                .andExpect(jsonPath("$.stays[0].breakfastIncluded").value(false))
                .andExpect(jsonPath("$.stays[0].price.totalAmount").value(132000))
                .andExpect(jsonPath("$.stays[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.stays[0].price.taxAmount").value(12000))
                .andExpect(jsonPath("$.stays[0].price.nightlyRates[0].amount").value(132000))
                .andExpect(jsonPath("$.partial").value(false));
    }

    @Test
    @DisplayName("날짜별 내역과 세액을 주지 않는 공급사면 그 필드가 아예 빠진다")
    void omitsOptionalPriceFields() throws Exception {
        given(service.search(any(SearchCriteria.class))).willReturn(new StaySearchResult(
                List.of(stayWithTotalOnly()),
                List.of(SupplierStatus.ok(SupplierCode.of("B"))),
                0, 0, 0));

        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-01").param("checkOut", "2026-09-04")
                        .param("adults", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stays[0].price.totalAmount").value(452000))
                .andExpect(jsonPath("$.stays[0].price.taxAmount").doesNotExist())
                .andExpect(jsonPath("$.stays[0].price.nightlyRates").doesNotExist());
    }

    @Test
    @DisplayName("부분 실패도 200 이고, 무엇을 못 봤는지가 본문에 남는다")
    void reportsPartialFailureWithTwoHundred() throws Exception {
        given(service.search(any(SearchCriteria.class))).willReturn(new StaySearchResult(
                List.of(),
                List.of(SupplierStatus.ok(SupplierCode.of("A")),
                        SupplierStatus.failed(SupplierCode.of("B"), FailureReason.TIMEOUT, "x")),
                2, 1, 0));

        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-01").param("checkOut", "2026-09-04")
                        .param("adults", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.suppliers[0].status").value("OK"))
                .andExpect(jsonPath("$.suppliers[0].reason").doesNotExist())
                .andExpect(jsonPath("$.suppliers[1].status").value("FAILED"))
                .andExpect(jsonPath("$.suppliers[1].reason").value("TIMEOUT"))
                // 결과가 빈 이유가 셋 다 구분된다.
                .andExpect(jsonPath("$.excludedSoldOut").value(2))
                .andExpect(jsonPath("$.excludedUnmapped").value(1))
                .andExpect(jsonPath("$.excludedOverCapacity").value(0));
    }

    // ── 요청 검증 ────────────────────────────────────────────────

    @Test
    @DisplayName("체크아웃이 체크인보다 앞서면 400 이다")
    void rejectsReversedDates() throws Exception {
        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-04").param("checkOut", "2026-09-01")
                        .param("adults", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("성인이 0명이면 400 이다")
    void rejectsZeroAdults() throws Exception {
        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-01").param("checkOut", "2026-09-04")
                        .param("adults", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인원을 안 보내면 400 이다 — 우리가 정해주지 않는다")
    void rejectsMissingGuests() throws Exception {
        mvc.perform(get(URL)
                        .param("checkIn", "2026-09-01").param("checkOut", "2026-09-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("날짜 형식이 깨지면 400 이다")
    void rejectsMalformedDate() throws Exception {
        mvc.perform(get(URL)
                        .param("checkIn", "2026/09/01").param("checkOut", "2026-09-04")
                        .param("adults", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    /** 응답 형태만 보는 픽스처다. 요금은 자기 기간(1박)에 대해 완결되어 있으면 된다. */
    private static Stay stayWithBreakdown() {
        StayPrice price = StayPrice.fromNightlyRates(
                List.of(new NightlyRate(
                        LocalDate.parse("2026-09-01"),
                        Money.of(120_000, "KRW"),
                        Money.of(12_000, "KRW"))),
                DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02")));
        return new Stay(11, "Riverside Hotel Seoul", 22, "Deluxe Twin",
                2, new Availability(1), SupplierCode.of("A"), false, price);
    }

    private static Stay stayWithTotalOnly() {
        return new Stay(33, "Riverside Hotel Seoul", 44, "Deluxe Twin Room",
                2, new Availability(3), SupplierCode.of("B"), true,
                StayPrice.fromTotal(Money.of(452_000, "KRW")));
    }
}
