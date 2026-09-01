package com.channel.mock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 두 공급사의 재고·요금 API 를 흉내낸다.
 *
 * <p>요청 파라미터는 무시하고 고정 응답을 준다. 이 Mock 의 목적은 데이터 다양성이 아니라
 * <b>연동 견고성을 검증할 수 있는 세 가지 상황(정상 / 장애 / 무응답)을 재현하는 것</b>이다.
 *
 * <p>두 공급사의 실패 통지 방식이 다르다는 점이 핵심이다. A 는 HTTP 상태 코드로 알리고,
 * B 는 장애 상황에서도 HTTP 200 을 주면서 본문 {@code resultCode} 로만 알린다. 어댑터가
 * 이 둘을 같은 실패로 정규화하는지 확인하려면 Mock 이 그 차이를 그대로 재현해야 한다.
 *
 * <p>모드 변경:
 * <pre>
 * curl -X POST 'http://localhost:9090/control/a/mode?value=no-response'
 * curl -X POST 'http://localhost:9090/control/b/mode?value=error'
 * curl -X POST 'http://localhost:9090/control/a/mode?value=normal'
 * </pre>
 */
@RestController
public class MockSupplierController {

    /** normal | error | no-response */
    private static final String MODE_NORMAL = "normal";
    private static final String MODE_ERROR = "error";
    private static final String MODE_NO_RESPONSE = "no-response";

    /**
     * 무응답 모드에서 붙잡고 있는 시간. 클라이언트 타임아웃(응답 3초)보다 충분히 길기만 하면
     * 되므로 넉넉하게 잡는다. 연결은 되지만 응답이 오지 않는 상황을 재현한다.
     */
    private static final Duration NO_RESPONSE_HOLD = Duration.ofMinutes(10);

    private final Map<String, String> modes = new ConcurrentHashMap<>();

    // ── 모드 제어 ────────────────────────────────────────────────

    @PostMapping("/control/{supplier}/mode")
    public Map<String, String> setMode(@PathVariable String supplier, @RequestParam String value) {
        modes.put(supplier, value);
        return Map.of(supplier, value);
    }

    @GetMapping("/control/modes")
    public Map<String, String> modes() {
        return Map.copyOf(modes);
    }

    // ── ① 숙소 목록 (정적 콘텐츠) ────────────────────────────────
    // 장애 모드를 걸지 않았다. 매핑을 만드는 단계가 실패하면 어떻게 할지는 별도 주제다.

    @GetMapping(value = "/a/v1/hotels", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> hotelsA() {
        return ResponseEntity.ok(A_HOTELS);
    }

    @GetMapping(value = "/b/api/properties", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> propertiesB() {
        return ResponseEntity.ok(B_PROPERTIES);
    }

    // ── ② 재고·요금 조회 ─────────────────────────────────────────

    @GetMapping(value = "/a/v1/availability", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> availabilityA(@RequestParam String hotelCodes) {
        return switch (modeOf("a")) {
            // A 는 실패를 HTTP 상태 코드로 알린다.
            case MODE_ERROR -> ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                          {"error":"SERVICE_UNAVAILABLE","message":"temporarily unavailable"}""");
            case MODE_NO_RESPONSE -> hold();
            default -> ResponseEntity.ok(A_AVAILABILITY);
        };
    }

    @GetMapping(value = "/b/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchB(@RequestParam String propertyIds) {
        return switch (modeOf("b")) {
            // B 는 장애 상황에서도 HTTP 200 이다. 본문의 resultCode 로만 실패를 알린다.
            case MODE_ERROR -> ResponseEntity.ok("""
                          {"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}""");
            case MODE_NO_RESPONSE -> hold();
            default -> ResponseEntity.ok(B_SEARCH);
        };
    }

    // ── 내부 ─────────────────────────────────────────────────────

    private String modeOf(String supplier) {
        return modes.getOrDefault(supplier, MODE_NORMAL);
    }

    /** 연결은 유지한 채 응답을 주지 않는다. 클라이언트 타임아웃이 걸리는 것을 확인하기 위한 것. */
    private ResponseEntity<String> hold() {
        try {
            Thread.sleep(NO_RESPONSE_HOLD);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.ok("{}");
    }

    // ── 고정 응답 ────────────────────────────────────────────────
    // 공급사 스펙의 구조만 지킨다. 값 자체에는 의미를 두지 않는다.
    //
    // 검증에 쓰이는 지점:
    //  - A-10023 / B77120 은 같은 숙소지만 공통 키가 없다 → 병합하지 않고 각각 노출하는지
    //  - A 는 조식 미포함 429,000 / B 는 조식 포함 452,000 → 조건 차이가 함께 노출되는지
    //  - A-10044 는 2026-09-02 재고가 0 → 연박 판정(min)이 0을 만들어 제외하는지

    private static final String A_HOTELS = """
            {
              "items": [
                {
                  "hotelCode": "A-10023",
                  "hotelName": "Riverside Hotel Seoul",
                  "roomTypes": [
                    { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 }
                  ]
                },
                {
                  "hotelCode": "A-10044",
                  "hotelName": "Namsan Garden Stay",
                  "roomTypes": [
                    { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 }
                  ]
                }
              ]
            }""";

    private static final String A_AVAILABILITY = """
            {
              "items": [
                {
                  "hotelCode": "A-10023",
                  "hotelName": "Riverside Hotel Seoul",
                  "roomTypeCode": "DLX-TWN",
                  "roomTypeName": "Deluxe Twin",
                  "maxOccupancy": 2,
                  "breakfastIncluded": false,
                  "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                    { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                    { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
                  ]
                },
                {
                  "hotelCode": "A-10044",
                  "hotelName": "Namsan Garden Stay",
                  "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double",
                  "maxOccupancy": 2,
                  "breakfastIncluded": false,
                  "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
                    { "date": "2026-09-02", "remainingRooms": 0, "nightlyRate": 99000, "taxAmount": 9900 },
                    { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 88000, "taxAmount": 8800 }
                  ]
                }
              ]
            }""";

    private static final String B_PROPERTIES = """
            {
              "resultCode": "0000",
              "resultMessage": "SUCCESS",
              "data": {
                "items": [
                  {
                    "propertyId": "B77120",
                    "propertyName": "Riverside Hotel Seoul",
                    "rooms": [
                      { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 }
                    ]
                  }
                ]
              }
            }""";

    private static final String B_SEARCH = """
            {
              "resultCode": "0000",
              "resultMessage": "SUCCESS",
              "data": {
                "items": [
                  {
                    "propertyId": "B77120",
                    "propertyName": "Riverside Hotel Seoul",
                    "roomId": "R-401",
                    "roomName": "Deluxe Twin Room",
                    "maxOccupancy": 2,
                    "breakfastIncluded": true,
                    "currency": "KRW",
                    "totalPrice": 452000,
                    "taxIncluded": true,
                    "inventory": [
                      { "date": "2026-09-01", "remainingRooms": 3 },
                      { "date": "2026-09-02", "remainingRooms": 1 },
                      { "date": "2026-09-03", "remainingRooms": 5 }
                    ]
                  }
                ]
              }
            }""";
}
