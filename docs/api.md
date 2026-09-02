# API 명세

요청·응답 스키마와 상태 코드 정책을 적는다. **왜 그렇게 정했는지는
[README](../README.md) 에 있다.**

| 엔드포인트 | 용도 |
| --- | --- |
| `GET /api/v1/stays/search` | 통합 검색 (고객용) |
| `POST /internal/suppliers/sync` | 숙소 목록 동기화 (운영용) |

인증은 걸려 있지 않다. `/internal/**` 은 이대로 밖에 열어둘 통로가 아니다.

---

## GET /api/v1/stays/search

### 요청

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `checkIn` | `YYYY-MM-DD` | ✅ | 체크인일 |
| `checkOut` | `YYYY-MM-DD` | ✅ | 체크아웃일. **숙박일에 포함되지 않는다** |
| `adults` | int | ✅ | 성인 수. 1 이상 |
| `children` | int | | 아동 수. 기본 `0` |

`adults` 에 기본값을 두지 않는다. 인원은 재고 판정과 요금에 모두 영향을 주므로, 안 보냈을 때
우리가 정하면 묻지도 않은 조건으로 검색해 준 셈이 된다.

지역·키워드 필터는 없다. 공급사가 지역 정보를 주지 않으므로 조회 대상은 항상 보유 숙소 전체다.

```
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

09-01 체크인 / 09-04 체크아웃은 **3박**이고, 재고와 요금을 확인하는 날짜는 09-01·09-02·09-03 이다.

### 응답 (200)

| 필드 | 타입 | 항상 있음 | 설명 |
| --- | --- | --- | --- |
| `stays[]` | array | ✅ | 예약 가능한 상품. 비어 있을 수 있다 |
| `suppliers[]` | array | ✅ | **조회를 시도한** 공급사별 상태. 물어볼 숙소가 없어 부르지 않은 공급사는 없다 |
| `partial` | boolean | ✅ | 일부 공급사를 못 봤는가 |
| `excludedSoldOut` | int | ✅ | 재고 0 으로 제외한 상품 수 |
| `excludedUnmapped` | int | ✅ | 매핑에 없어 제외한 상품 수 |
| `excludedOverCapacity` | int | ✅ | 인원 초과로 제외한 상품 수 |

#### `stays[]`

| 필드 | 타입 | 항상 있음 | 설명 |
| --- | --- | --- | --- |
| `propertyId` | long | ✅ | **내부** 숙소 식별자. 공급사 코드가 아니다 |
| `propertyName` | string | ✅ | 숙소명 |
| `roomTypeId` | long | ✅ | **내부** 객실 타입 식별자 |
| `roomTypeName` | string | ✅ | 객실 타입명 |
| `maxOccupancy` | int | ✅ | 객실 1실 최대 수용 인원 (성인+아동) |
| `availableRooms` | int | ✅ | 요청 기간 **전체**를 예약할 수 있는 객실 수. 항상 1 이상 |
| `supplier` | string | ✅ | 출처 공급사 코드 |
| `breakfastIncluded` | boolean | ✅ | 요금에 조식이 포함되는가 |
| `price` | object | ✅ | 아래 참고 |

`availableRooms` 는 요청 기간 각 숙박일 잔여 수의 **최솟값**이다. 0 인 상품은 결과에 담기지
않으므로 이 값은 항상 1 이상이다.

#### `stays[].price`

| 필드 | 타입 | 항상 있음 | 설명 |
| --- | --- | --- | --- |
| `totalAmount` | long | ✅ | 요청 기간 전체 결제 예정 금액. **세금 포함** |
| `currency` | string | ✅ | ISO 4217 |
| `taxAmount` | long | | 세액. **주는 공급사에서만** |
| `nightlyRates[]` | array | | 날짜별 내역. **주는 공급사에서만** |

`taxAmount` 와 `nightlyRates` 는 없으면 **필드 자체가 나가지 않는다.** `0` 이나 `[]` 로 내보내면
"세액이 0원", "내역이 비었다"와 구분되지 않는다.

금액은 통화의 최소 단위 정수다 (KRW 는 원, 소수점 없음).

#### `stays[].price.nightlyRates[]`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `date` | `YYYY-MM-DD` | 숙박일 |
| `amount` | long | 그날 고객이 실제로 내는 금액 (**세금 포함**) |
| `taxAmount` | long | 그날 붙는 세금 |

`sum(nightlyRates[].amount) == totalAmount` 다.

#### `suppliers[]`

| 필드 | 타입 | 항상 있음 | 설명 |
| --- | --- | --- | --- |
| `supplier` | string | ✅ | 공급사 코드 |
| `status` | `OK` \| `FAILED` | ✅ | 조회 결과 |
| `reason` | string | | 실패 사유. `FAILED` 일 때만 |

`reason` 값은 아래 중 하나다. 공급사가 실패를 어떻게 알렸는지(상태 코드인지 본문 코드인지)는
드러나지 않는다 — 어댑터가 같은 사유로 정규화한다.

| `reason` | 뜻 | 재시도 |
| --- | --- | --- |
| `TIMEOUT` | 제때 응답하지 않음 (연결·응답·검색 전체 상한) | 가능 |
| `SUPPLIER_ERROR` | 공급사가 자기 쪽 오류를 알림 | 가능 |
| `RATE_LIMITED` | 호출 한도 초과 | 잠시 뒤 |
| `UNAUTHORIZED` | 인증 실패 | 무의미 (설정 문제) |
| `INVALID_REQUEST` | 우리가 잘못 보냄 | 무의미 |
| `MALFORMED_RESPONSE` | 응답을 표준 모델로 못 옮김 | 무의미 |
| `UNKNOWN` | 위 어디에도 넣기 어려움 | — |

### 결과가 비었을 때 무엇을 봐야 하는가

`stays` 가 비어 있는 이유는 넷이고, 응답만으로 구분된다.

| 상황 | 응답 모양 | 누가 할 일인가 |
| --- | --- | --- |
| 전부 만실 | `excludedSoldOut > 0`, `partial: false` | 고객이 날짜를 바꾼다 |
| 인원이 안 맞음 | `excludedOverCapacity > 0` | 고객이 인원을 줄인다 |
| 공급사를 못 봄 | `partial: true` | 우리가 공급사 상태를 본다 |
| 매핑이 모자람 | `excludedUnmapped > 0` | **우리가 동기화를 돌린다** |
| 물어볼 숙소가 없음 | `suppliers: []` | 우리가 동기화를 돌린다 |

### 예시 — 정상

```jsonc
{
  "stays": [
    {
      "propertyId": 1,
      "propertyName": "Riverside Hotel Seoul",
      "roomTypeId": 1,
      "roomTypeName": "Deluxe Twin",
      "maxOccupancy": 2,
      "availableRooms": 1,
      "supplier": "A",
      "breakfastIncluded": false,
      "price": {
        "totalAmount": 429000,
        "currency": "KRW",
        "taxAmount": 39000,
        "nightlyRates": [
          { "date": "2026-09-01", "amount": 132000, "taxAmount": 12000 },
          { "date": "2026-09-02", "amount": 165000, "taxAmount": 15000 },
          { "date": "2026-09-03", "amount": 132000, "taxAmount": 12000 }
        ]
      }
    }
  ],
  "suppliers": [ { "supplier": "A", "status": "OK" } ],
  "partial": false,
  "excludedSoldOut": 1,
  "excludedUnmapped": 0,
  "excludedOverCapacity": 0
}
```

### 예시 — 부분 실패

공급사 하나가 죽어도 **200** 이다. 부분 실패는 오류가 아니라 불완전한 성공이다.

```jsonc
{
  "stays": [ /* 살아남은 공급사의 결과 */ ],
  "suppliers": [
    { "supplier": "A", "status": "OK" },
    { "supplier": "B", "status": "FAILED", "reason": "TIMEOUT" }
  ],
  "partial": true,
  "excludedSoldOut": 0,
  "excludedUnmapped": 0,
  "excludedOverCapacity": 0
}
```

### 예시 — 총액만 주는 공급사

세액과 날짜별 내역이 **없는 채로** 나간다.

```jsonc
{
  "propertyId": 3, "propertyName": "Riverside Hotel Seoul",
  "roomTypeId": 3, "roomTypeName": "Deluxe Twin Room",
  "maxOccupancy": 2, "availableRooms": 3,
  "supplier": "B", "breakfastIncluded": true,
  "price": { "totalAmount": 452000, "currency": "KRW" }
}
```

### 에러 (400)

요청 자체가 성립하지 않는 경우다. **공급사 조회 실패는 여기 오지 않는다** — 그건 200 이고
`partial` 로 표현된다.

```jsonc
{ "error": "INVALID_REQUEST", "message": "체크아웃은 체크인보다 뒤여야 한다: 2026-09-04 ~ 2026-09-01" }
```

| 경우 | 예 |
| --- | --- |
| 체크아웃이 체크인보다 앞서거나 같음 | `checkIn=2026-09-04&checkOut=2026-09-01` |
| 성인이 1명 미만 | `adults=0` |
| 아동 수가 음수 | `children=-1` |
| 필수 파라미터 누락 | `adults` 없음 |
| 날짜 형식 오류 | `checkIn=2026/09/01` |

---

## POST /internal/suppliers/sync

공급사 숙소 목록을 받아 매핑을 채운다. 기동 시 1회와 주기적 갱신으로도 돌지만, 다음 주기까지
기다릴 수 없을 때 쓴다.

요청 본문은 없다.

### 응답 (200)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `suppliers[].supplier` | string | 공급사 코드 |
| `suppliers[].status` | `OK` \| `FAILED` | 동기화 결과 |
| `suppliers[].properties` | int | 반영한 숙소 수. 실패면 `0` |
| `suppliers[].roomTypes` | int | 반영한 객실 타입 수. 실패면 `0` |
| `suppliers[].reason` | string | 실패 사유. `FAILED` 일 때만. 값은 검색과 같은 목록 |
| `properties` | int | 전체 합계 |
| `roomTypes` | int | 전체 합계 |
| `partial` | boolean | 일부 공급사가 실패했는가 |

```jsonc
{
  "suppliers": [
    { "supplier": "A", "status": "OK", "properties": 2, "roomTypes": 2 },
    { "supplier": "B", "status": "FAILED", "properties": 0, "roomTypes": 0, "reason": "TIMEOUT" }
  ],
  "properties": 2,
  "roomTypes": 2,
  "partial": true
}
```

여기서도 부분 실패는 200 이다. 실패한 공급사의 **기존 매핑은 지워지지 않는다.**

---

## 상태 코드 정책

| 코드 | 언제 |
| --- | --- |
| `200` | 요청이 성립했다. 공급사 일부가 실패했어도 여기에 해당한다 |
| `400` | 요청 자체가 성립하지 않는다 (조건 모순, 필수 값 누락, 형식 오류) |
| `404` | 없는 경로 |

5xx 를 의도적으로 내보내는 경로는 없다. **공급사 장애를 5xx 로 옮기지 않는 것이 이 API 의
핵심 규칙**이다 — 그렇게 하면 공급사 하나의 장애가 우리 서비스의 장애로 보이고, 클라이언트는
살아 있는 나머지 결과까지 버리게 된다.
