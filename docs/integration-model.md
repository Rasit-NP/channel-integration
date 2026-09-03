# 통합 상품 모델

공급사 응답의 **어느 필드가 표준 모델의 어디로, 어떤 규칙으로 가는지**를 적는다.

**왜 이 표준을 골랐는지는 [README §통합 상품 모델](../README.md) 에 있다.** 클라이언트가 받는
JSON 은 [api.md](api.md) 가, 새 공급사를 붙이는 절차는 [adding-supplier.md](adding-supplier.md) 가
맡는다. 여기에는 **안쪽 대응**만 쓴다 — 바깥으로 나가는 모양이 아니라, 들어온 것이 무엇이 되는가다.

---

## 표준 모델

`domain` 패키지의 타입들이다. api.md 의 JSON 과 이름이 비슷하지만 **같지 않다** — 저쪽은 표현이고
이쪽은 모델이다.

| 타입 | 필드 | 생성 시점에 막는 것 |
| --- | --- | --- |
| `Money` | `amount`(최소 단위 정수), `currency` | 음수 금액, ISO 4217 3자리가 아닌 통화. 통화가 다르면 합산 불가 |
| `NightlyRate` | `date`, `netAmount`, `taxAmount` | 셋 다 필수. `grossAmount()` = net + tax |
| `StayPrice` | `totalAmount`, `nightlyRates[]`, `taxAmount` | `totalAmount` 필수. 나머지 둘은 **주는 공급사만** 채운다 |
| `DailyInventory` | `date`, `remainingRooms` | 음수 재고 |
| `Availability` | `availableRooms` | 음수 |
| `DateRange` | `checkIn`, `checkOut` | 체크아웃이 체크인 이하. **체크아웃일은 숙박일이 아니다** |
| `SearchCriteria` | `dates`, `adults`, `children` | 성인 1명 미만, 아동 음수 |
| `SupplierCode` | `value` | 빈 값 |
| `Stay` | 내부 식별자·이름·`maxOccupancy`·`availability`·`supplier`·`breakfastIncluded`·`price` | 이름·재고·출처·요금 누락 |

경계에 놓인 두 타입은 `port` 에 있다. **공급사 코드를 그대로 들고 있다**는 점이 `Stay` 와 다르다.

| 타입 | 필드 |
| --- | --- |
| `SupplierProperty` | `propertyCode`, `propertyName`, `roomTypes[]` |
| `SupplierRoomType` | `roomTypeCode`, `roomTypeName`, `maxOccupancy` (**1 이상**) |
| `SupplierOffer` | `propertyCode`, `propertyName`, `roomTypeCode`, `roomTypeName`, `maxOccupancy`, `breakfastIncluded`, `price`, `inventories[]` |

`SupplierOffer.maxOccupancy` 에는 하한이 없다. `SupplierRoomType` 과 달리 여기서 거르지 않고,
인원 판정이 검색 단계에서 따로 돈다.

---

## 숙소 목록 → 매핑

두 공급사 모두 목록 응답에서 **코드만 저장된다.** 이름과 수용 인원은 저장하지 않는다
(README §이름을 저장하지 않는 이유).

| 표준 | 공급사 A | 공급사 B | 규칙 |
| --- | --- | --- | --- |
| `propertyCode` | `hotelCode` | `propertyId` | 없으면 그 숙소를 버린다 |
| `propertyName` | `hotelName` | `propertyName` | **없으면 코드를 이름으로 쓴다** |
| `roomTypeCode` | `roomTypes[].roomTypeCode` | `rooms[].roomId` | 없으면 그 객실 타입만 버린다 |
| `roomTypeName` | `roomTypes[].roomTypeName` | `rooms[].roomName` | 없으면 코드를 이름으로 쓴다 |
| `maxOccupancy` | `roomTypes[].maxOccupancy` | `rooms[].maxOccupancy` | 없거나 1 미만이면 그 객실 타입만 버린다 |

**이 단계의 `maxOccupancy` 는 저장되지 않는다.** 하는 일은 하나뿐이다 — 1 미만이면 그 객실 타입에
매핑을 만들지 않고, 그러면 나중에 그 객실 타입의 상품이 와도 매핑에 없어 결과에서 빠진다.
값 자체는 재고·요금 응답에서 매번 새로 온다.

---

## 재고·요금 → 표준 상품

| 표준 (`SupplierOffer`) | 공급사 A | 공급사 B |
| --- | --- | --- |
| `propertyCode` | `hotelCode` | `propertyId` |
| `propertyName` | `hotelName` → 없으면 코드 | `propertyName` → 없으면 코드 |
| `roomTypeCode` | `roomTypeCode` | `roomId` |
| `roomTypeName` | `roomTypeName` → 없으면 코드 | `roomName` → 없으면 코드 |
| `maxOccupancy` | `maxOccupancy` | `maxOccupancy` |
| `breakfastIncluded` | `breakfastIncluded` → **없으면 `false`** | `breakfastIncluded` → **없으면 `false`** |
| `price.totalAmount` | `Σ(dailyRates[].nightlyRate + taxAmount)` | `totalPrice` 그대로 |
| `price.nightlyRates[]` | `dailyRates[]` 에서 만든다 | **없음** |
| `price.taxAmount` | `Σ dailyRates[].taxAmount` | **없음** |
| `inventories[]` | `dailyRates[].remainingRooms` | `inventory[].remainingRooms` |
| — | — | `taxIncluded` 가 참이 아니면 **상품을 만들지 않는다** |

### 요금 정규화

| 공급사가 주는 것 | 쓰는 팩토리 | 하는 일 |
| --- | --- | --- |
| 날짜별 단가 + 세액 (A) | `StayPrice.fromNightlyRates(rates, dates)` | 요청 기간 밖 날짜를 버리고, 남은 날의 `(단가+세액)` 을 합쳐 총액을 만든다 |
| 기간 총액, 세금 포함 (B) | `StayPrice.fromTotal(total)` | 그대로 총액으로 삼는다. 날짜별로 쪼개지 않는다 |

**A 는 요청 기간을 넘겨야 하고 B 는 넘길 수 없다.** A 는 어느 날짜를 합칠지 정해야 하지만 B 는
자를 대상이 없기 때문이다. 규칙 자체는 `StayPrice` 가 갖고, 어댑터는 팩토리를 고르기만 한다.

A 의 날짜별 요금은 **`nightlyRate` 와 `taxAmount` 가 둘 다 있어야** 만들어진다. 하루치라도 한쪽이
비면 그 날 요금이 안 생기고, 그러면 날짜 집합이 요청 기간과 어긋나 **그 상품 전체가 빠진다.**
세액을 따로 주는 공급사에서 세액만 빠지면 세금 포함 총액을 만들 수 없기 때문이다.

### 재고 정규화

날짜별 잔여 수를 그대로 담고, **판정은 하지 않는다.** 요청 기간을 알아야 하고 규칙도 공급사가
아니라 우리 정책이라, `Availability.forStay(inventories, dates)` 가 검색 단계에서 최솟값을 취한다.

날짜가 없거나 잔여 수가 음수인 항목은 **그 날짜만** 버린다. 빠진 날은 판정에서 재고 0 으로
취급되므로 보수적인 쪽이다.

### 값이 어디서 확정되는가

| 값 | 확정되는 곳 |
| --- | --- |
| 요금의 **형태**(총액·세금 포함) | 어댑터가 팩토리를 고르고, 합산 규칙은 `StayPrice` |
| 예약 가능 객실 수 | `Availability.forStay` — 요청 기간이 필요하다 |
| **내부 식별자** | `StaySearchService` — DB 매핑이 필요하다 |
| 인원 수용 여부 | `StaySearchService` — 요청 인원이 필요하다 |
| 없는 필드를 응답에서 빼는 것 | `api` 계층 |

어댑터는 **형태**를 통일하고 `application` 이 **정체**를 확정한다. 계층별 책임은
[architecture.md](architecture.md) 에 있다.

---

## 상품이 결과에서 빠지는 모든 경로

두 단계에서 빠지고, **한쪽만 세어진다.**

### 어댑터 단계 — 세지 않는다

| 조건 | 무엇이 빠지나 |
| --- | --- |
| 숙소·객실 타입 코드가 없음 | 그 항목 |
| 통화 또는 수용 인원이 없음 | 그 상품 |
| A — 날짜별 요금이 아예 없음 | 그 상품 |
| A — 요청 기간의 숙박일과 요금 날짜가 어긋남 (모자람·중복) | 그 상품 |
| B — 총액이 없음 | 그 상품 |
| B — `taxIncluded` 가 참이 아님 | 그 상품 |
| 금액·통화가 표준 모델을 못 만드는 값 | 그 상품 |
| 객실 타입 수용 인원이 1 미만 | 그 객실 타입만 (숙소는 남는다) |
| 재고 날짜가 없거나 잔여 수가 음수 | 그 날짜만 (상품은 남는다) |

**이 단계의 폐기는 응답 어디에도 안 잡힌다.** 알려진 구멍이고 JOURNAL 열린 문제에 있다.

### 검색 단계 — 세 가지로 나눠 센다

매핑에 없음 / 인원 초과 / 재고 0. 각각이 무엇을 뜻하고 누가 무엇을 해야 하는지는
[README §무엇을 왜 뺐는지](../README.md) 에 있다.

---

## 표준 모델이 잃는 것

이 표준을 고르면서 표현하지 못하게 된 것들이다. **알고 택한 손실**과 **확인 과정에서 드러난
손실**을 함께 적는다.

| 잃는 것 | 어느 공급사 | 그래서 불가능해지는 것 |
| --- | --- | --- |
| 날짜별 요금 내역 | B | "1박만 취소 시 환불액", "특정 날짜만 비교" |
| 세액 분리 | B | 영수증에 세금을 따로 표시 |
| **총액이 요청 기간에 대한 값인지 검증** | B | 공급사가 다른 기간의 총액을 줘도 알 수 없다. A 는 날짜가 있어 검증되지만 B 는 믿는 수밖에 없다 |
| **"조식 여부를 모른다"는 상태** | 양쪽 | 공급사가 값을 안 주면 `false` 로 단정한다. 응답의 `breakfastIncluded: false` 는 "미포함"과 "모름"을 구분하지 못한다. 고객에게 없는 조식을 약속하지 않는 쪽이라 보수적이긴 하다 |
| **이름이 없다는 사실** | 양쪽 | 이름이 안 오면 코드를 이름으로 쓴다. 고객 화면에 `A-10023` 같은 값이 그대로 보일 수 있다 |
| 팔지 않는 숙소의 이름·수용 인원 | 양쪽 | 매핑에는 코드만 있으므로, 재고·요금 응답에 안 잡히는 숙소는 이름을 알 방법이 없다 |

앞의 둘은 표준을 정할 때 알고 택한 것이고(README §표준 요금), 나머지는 **필드를 하나씩 대조하다
드러난 것**이다. 고치자는 목록이 아니라, 이 모델 위에서 무엇을 만들 수 없는지의 목록이다.

---

## 표준을 넓혀야 할 때

새 공급사가 지금 모델에 안 들어가면 **어느 쪽이 문제인지 먼저 가른다.**

| 상황 | 판단 |
| --- | --- |
| 어댑터에서 흡수 가능 | 표준을 그대로 둔다. 대부분 여기다 |
| 표준에 자리가 없다 (예: 취소 규정, 지역) | **선택 필드로** 추가하고, 주는 공급사만 채운다 |
| 표준의 뜻이 달라져야 한다 (예: 세금 별도 총액도 허용) | 기존 공급사 응답의 의미가 함께 바뀌므로, 두 공급사 데이터를 모두 놓고 다시 정한다 |

**없는 값을 지어내서 채우지 않는다.** 총액을 날짜 수로 나누거나 세액을 추정해 더하는 것은
정규화가 아니라 날조이고, 그 값이 고객에게 나가면 실제 결제액과 어긋난다. 채울 수 없으면
**그 상품을 만들지 않는 쪽**을 택해 왔다.
