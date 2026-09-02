package com.channel.integration.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.channel.integration.application.StaySearchService;
import com.channel.integration.domain.DateRange;
import com.channel.integration.domain.SearchCriteria;

/**
 * 통합 검색 엔드포인트.
 *
 * <p>검색 조건은 날짜와 인원뿐이고 조회 대상은 보유 숙소 전체다. 공급사가 지역 정보를 주지
 * 않으므로 지역·키워드 필터는 다루지 않는다.
 *
 * <p>인원을 기본값으로 채우지 않는다. 몇 명이 묵는지는 요금과 재고 판정에 모두 영향을 주는
 * 값이라, 클라이언트가 안 보냈을 때 우리가 정하면 <b>묻지도 않은 조건으로 검색해 준 셈</b>이
 * 된다. 없으면 400 이다.
 */
@RestController
class StaySearchController {

    private final StaySearchService service;

    StaySearchController(StaySearchService service) {
        this.service = service;
    }

    /**
     * 검색은 블로킹이다. 여러 공급사를 동시에 기다리는 일은 리액티브로 하고, 그것을 값으로
     * 바꾸는 자리는 여기 하나다.
     *
     * <p>체크아웃일은 숙박일에 포함되지 않는다 (09-01 체크인 / 09-04 체크아웃 = 3박).
     */
    @GetMapping("/api/v1/stays/search")
    StaySearchResponse search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam(defaultValue = "0") int children) {

        // 조건이 말이 되는지는 도메인이 판단한다. 어긋나면 예외가 나고 400 으로 번역된다.
        SearchCriteria criteria =
                new SearchCriteria(DateRange.of(checkIn, checkOut), adults, children);

        return StaySearchResponse.from(service.search(criteria));
    }
}
