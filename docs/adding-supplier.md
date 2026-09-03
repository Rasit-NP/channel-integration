# 신규 공급사 추가 절차

**이 문서는 공급사 B 를 실제로 붙이면서 고친 자리를 그대로 적은 것이다.** 추측한 절차가 아니라
한 번 밟은 경로다. 왜 이런 구조인지는 [README §신규 공급사 추가 방법](../README.md) 에,
붙이면서 바뀐 판단은 [JOURNAL](../JOURNAL.md) 에 있다. 여기에는 **무엇을 어떤 순서로 만드는지**만
쓴다.

---

## 늘어나는 것과 안 바뀌는 것

B 를 추가한 커밋(`c05b515`)의 실측이다.

| | 파일 | 줄 |
| --- | --- | --- |
| **새로 생김** | `adapter/b/` 여섯 파일 | 459 |
| **고침** | `application.yaml` (`supplier.b.*` 블록) | +9 |
| **안 바뀜** | `domain`, `port`, `application`, `api` 운영 코드 | **0** |

이 0 이 이 구조의 주장이다. 새 공급사를 붙인 뒤 아래 명령으로 직접 확인한다.

```bash
git diff --stat HEAD -- src/main/java/com/channel/integration/domain src/main/java/com/channel/integration/port src/main/java/com/channel/integration/application src/main/java/com/channel/integration/api
```

여기에 무언가 찍히면 **경계가 새고 있다는 뜻이다.** 고치기 전에 왜 필요했는지부터 본다.

---

## 구현해야 하는 계약

### `port.SupplierAdapter`

```java
SupplierCode supplier();
int maxBatchSize();
Mono<SupplierFetchResult<List<SupplierProperty>>> fetchProperties();
Mono<SupplierFetchResult<List<SupplierOffer>>> fetchOffers(List<String> propertyCodes, SearchCriteria criteria);
```

| 메서드 | 지켜야 하는 것 |
| --- | --- |
| `supplier()` | 상수여야 한다. **이 값은 매핑 테이블의 키 일부다**(아래 §바꾸면 안 되는 것) |
| `maxBatchSize()` | 공급사가 한 번에 받아주는 숙소 코드 수. **선언만 한다** — 나누는 일은 `StaySearchService` 가 한다 |
| `fetchProperties()` | 조건 없는 전체 목록. 매핑을 만드는 데만 쓴다 |
| `fetchOffers(...)` | `propertyCodes` 가 `maxBatchSize()` 이하로 들어온다. 넘으면 호출하지 말고 실패 값으로 돌린다 |

**두 조회 모두 예외를 밖으로 던지지 않는다.** 어떤 경우에도 `SupplierFetchResult` 를 돌려준다.
부분 실패를 허용하려면 실패가 값이어야 하기 때문이다.

### `adapter.support.SupplierHttpProperties`

설정 레코드가 구현한다.

```java
String baseUrl();  String apiKey();
Duration connectTimeout();  Duration responseTimeout();  int maxBatchSize();
```

### 매퍼가 만들어야 하는 타입

| 조회 | 만들 것 |
| --- | --- |
| 숙소 목록 | `SupplierProperty(propertyCode, propertyName, List<SupplierRoomType>)`<br>`SupplierRoomType(roomTypeCode, roomTypeName, maxOccupancy)` |
| 재고·요금 | `SupplierOffer(propertyCode, propertyName, roomTypeCode, roomTypeName, maxOccupancy, breakfastIncluded, StayPrice, List<DailyInventory>)` |

식별자는 **공급사 코드 그대로** 담는다. 내부 식별자로 바꾸는 것은 `application` 의 일이다.
예약 가능 객실 수도 계산하지 않는다 — 요청 기간을 알아야 하고, 판정 규칙은 우리 정책이다.

---

## 파일 구성

`adapter.<코드>` 패키지 하나에 전부 들어간다. **다른 어댑터 패키지를 참조하지 않는다.**

| 파일 | 책임 | 가시성 |
| --- | --- | --- |
| `Supplier<X>Responses` | 그 공급사의 응답 DTO | **package-private** |
| `Supplier<X>Mapper` | 응답 → 표준 모델 | package-private |
| `Supplier<X>Adapter` | 호출 · 타임아웃 · 실패 판정 | package-private |
| `Supplier<X>Properties` | `supplier.<코드>.*` 바인딩 | package-private |
| `Supplier<X>Config` | `SupplierAdapter` 빈 등록 | package-private |
| `Supplier<X>Results` | 본문 코드로 실패를 알리는 공급사만 | package-private |

DTO 를 package-private 로 두는 것은 규약이 아니라 **컴파일러가 강제하는 경계**다. 다른 패키지에서
참조하면 빌드가 깨진다. A 는 다섯 파일, B 는 본문 코드 판정이 있어 여섯 파일이다.

---

## 절차

### 1. Mock 에 그 공급사를 추가한다

먼저 만든다. **고장을 재현할 상대가 없으면 견고성 코드를 짜도 확인할 수 없다.**

`MockSupplierController` 에 두 엔드포인트(숙소 목록, 재고·요금)를 더하고, 재고·요금 쪽에 세 모드를
넣는다. 모드 제어(`POST /control/{supplier}/mode`)는 이미 공급사 일반이라 손댈 필요가 없다.

| 모드 | 재현할 것 |
| --- | --- |
| `normal` | 고정 응답 |
| `error` | **그 공급사가 실제로 실패를 알리는 방식 그대로** |
| `no-response` | 연결은 유지하고 응답하지 않음 |

`error` 모드가 이 Mock 의 핵심이다. 상태 코드로 알리는 공급사와 200 + 본문 코드로 알리는 공급사가
**다르게 실패해야** 어댑터가 그 둘을 같은 실패로 정규화하는지 확인할 수 있다.

### 2. 설정 레코드

```java
@ConfigurationProperties(prefix = "supplier.<코드>")
record Supplier<X>Properties(
        @DefaultValue("http://localhost:9090") String baseUrl,
        @DefaultValue("local-dev-key") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration responseTimeout,
        @DefaultValue("<상한>") int maxBatchSize) implements SupplierHttpProperties {

    Supplier<X>Properties {
        maxBatchSize = SupplierHttpProperties.requireUsableBatchSize(maxBatchSize);
    }
}
```

검증을 빼면 상한이 0 일 때 검색이 묶음을 나누다 끝나지 않는다. 기동 때 막는 자리다.

### 3. 응답 DTO

`@JsonIgnoreProperties(ignoreUnknown = true)` 를 붙인다. 공급사가 필드를 추가하는 것은 우리 쪽
장애 사유가 아니다. 응답을 봉투(`resultCode` / `data`)에 담는 공급사라면, 봉투 타입들이 공통
인터페이스를 구현하게 해 코드 확인을 한 자리에서 하도록 한다.

### 4. 매퍼 — 요금 팩토리를 고른다

| 공급사가 주는 것 | 쓸 것 |
| --- | --- |
| 날짜별 단가(+세액) | `StayPrice.fromNightlyRates(rates, criteria.dates())` |
| 기간 총액(세금 포함) | `StayPrice.fromTotal(total)` |

**날짜별형은 요청 기간을 반드시 넘긴다.** 그래야 공급사가 요청하지 않은 날짜를 얹어 보내도 총액이
부풀지 않는다. 시그니처가 요구하므로 빠뜨릴 수 없다.

총액형인데 **세금 포함이 아니면 그 상품을 만들지 않는다.** 세액을 안 주는 공급사의 세금 별도
총액을 포함가로 바꿀 방법이 없고, 추정해 더하는 것은 지어내는 일이다.

변환할 수 없는 항목은 `null` 을 돌려 건너뛴다. **예외를 밖으로 흘리면 안 된다**(§빠뜨리기 쉬운 것).

### 5. 실패 판정 — 그 공급사의 방식대로

| 통지 방식 | 판정 |
| --- | --- |
| HTTP 상태 코드 | `retrieve()` 가 던지는 예외를 `HttpFailures.fromThrowable` 로 옮긴다. 추가 코드가 없다 |
| 항상 200 + 본문 코드 | `Supplier<X>Results` 를 두고 **본문을 옮기기 전에** 코드를 본다 |

두 번째 경우에도 **전송 계층 판정을 버리지 않는다.** 연결 실패·타임아웃·인프라가 낸 5xx 는
그 공급사에서도 상태 코드로 온다. 본문 코드 판정은 그 위에 얹히는 층이지 대체가 아니다.

**그 공급사의 코드 목록을 확인하고, 상태 코드 판정과 같은 해상도로 나눈다.** 잘못된 요청·인증
실패·호출 한도 초과가 각각 구분되지 않으면 클라이언트가 재시도 여부를 판단할 수 없다. 목록에 없는
코드만 `UNKNOWN` 으로 남긴다 — 모르는 것을 아는 사유로 밀어 넣지 않는다.

### 6. 어댑터

```java
return webClient.get().uri(...)
        .retrieve()
        .bodyToMono(<응답 타입>.class)
        .map(body -> /* 성공 판정 → 매퍼 */)
        .defaultIfEmpty(/* 본문 없음 → MALFORMED_RESPONSE */)
        .timeout(properties.responseTimeout())
        .onErrorResume(error -> Mono.just(/* HttpFailures 로 옮긴 실패 */));
```

앞에 빈 목록·묶음 초과를 먼저 거른다.

```java
if (propertyCodes == null || propertyCodes.isEmpty()) return Mono.just(SupplierFetchResult.success(List.of()));
if (propertyCodes.size() > maxBatchSize()) return Mono.just(/* INVALID_REQUEST 실패 값 */);
```

물어볼 숙소가 없는 것은 실패가 아니다. 묶음 초과는 실패지만 **예외가 아니라 값**이다 — 예외로
나가면 여러 공급사를 병합하는 쪽에서 그 하나 때문에 흐름이 끊긴다.

### 7. 빈 등록

```java
@Configuration
@EnableConfigurationProperties(Supplier<X>Properties.class)
class Supplier<X>Config {
    @Bean
    SupplierAdapter supplier<X>Adapter(WebClient.Builder builder, Supplier<X>Properties properties) {
        return new Supplier<X>Adapter(SupplierWebClients.create(builder, properties), properties);
    }
}
```

`PropertySyncService` 와 `StaySearchService` 는 `List<SupplierAdapter>` 를 주입받으므로 **등록만
하면 두 흐름에 자동으로 들어간다.** 목록에 이름을 더하는 곳은 없다.

### 8. `application.yaml`

`supplier.<코드>` 블록을 더한다. 기본값은 레코드에 있으므로 값이 다른 것만 적어도 되지만,
설정 표(README §설정)에 드러나도록 다 적는다.

### 9. 테스트 — 두 층

| 테스트 | 보는 것 | 안 보는 것 |
| --- | --- | --- |
| `Supplier<X>AdapterTest` | 변환, 실패 사유 분류, 묶음 경계 | 전송 계층 (`ExchangeFunction` 을 갈아끼운다) |
| `Supplier<X>MockIntegrationTest` | Netty 타임아웃, 직렬화 경로, Mock 3모드 | 검색 조립 |

한 층만 두면 안 되는 이유는 [architecture.md §테스트 전략](architecture.md) 에 있다.

어댑터 테스트에 최소한 이만큼은 넣는다.

- 요금이 표준 형태로 나오는가 (총액 · 세액 유무 · 내역 유무)
- 조식 포함 여부와 재고를 옮기는가
- 숙소 코드를 그 공급사 형식으로 묶어 보내는가
- 빈 목록이면 **호출하지 않는가** (호출하면 실패하는 `ExchangeFunction` 으로 확인)
- 묶음 초과면 호출하지 않고 실패 값을 주는가
- **그 공급사의 실패 통지 방식이 정규화되는가**
- 전송 계층 실패(4xx/5xx)도 여전히 사유로 분류되는가
- 타임아웃 · 해석 불가 본문
- 예외를 밖으로 던지지 않는가
- 못 옮기는 항목이 섞여도 나머지가 살아남는가

### 10. E2E 확장

`StaySearchMockIntegrationTest` 에 `supplier.<코드>.base-url` 을 더하고, 기대값을 갱신한다.
공급사가 둘 이상이 되면 여기서 **부분 실패가 실제로 관측된다** — 하나만 고장내고 나머지 결과가
나오는지 본다. 응답의 공급사 순서는 정해져 있지 않으므로 **자리(`[0]`)가 아니라 출처로 찾는다.**

```java
"$.stays[?(@.supplier=='B')]"
```

### 11. 경계 확인과 문서

위 §늘어나는 것과 안 바뀌는 것 의 `git diff --stat` 을 돌린 뒤 아래를 갱신한다.

- README 구현 현황 표 · 테스트 수 · §설정 표 · 실행 예시 응답
- `architecture.md` 패키지 맵 · 테스트 전략 표
- `api.md` — 응답 예시가 실제 응답과 달라졌다면
- `JOURNAL` — 붙이며 바뀐 판단

---

## 공급사마다 갈리는 결정

A 와 B 가 실제로 갈린 지점이다. 새 공급사를 붙일 때 답을 정해야 하는 항목이기도 하다.

| | 공급사 A | 공급사 B |
| --- | --- | --- |
| 실패 통지 | HTTP 상태 코드 | **항상 200** + 본문 결과 코드 |
| 응답 봉투 | 없음 | `resultCode` / `resultMessage` / `data` |
| 요금 | 날짜별 단가, 세금 별도 | 기간 총액, 세금 포함 |
| 요금 팩토리 | `fromNightlyRates(rates, dates)` | `fromTotal(total)` |
| 요청 기간 필요 | 필요 (자를 날짜가 있다) | 불필요 (자를 날짜가 없다) |
| 세액·날짜별 내역 | 응답에 나감 | **필드 자체가 안 나감** |
| 묶음 상한 | 50 | 50 (값은 같지만 어댑터마다 따로 선언한다) |
| 파일 수 | 5 | 6 (`Results` 추가) |

두 공급사의 묶음 상한은 지금 같은 값이지만, **검색 로직은 그 값이 같은지 알지 못한다.** 어댑터가
선언한 값에 맞춰 나눌 뿐이라, 상한이 다른 공급사가 들어와도 고칠 곳이 없다. 상한이 다를 때 실제로
나뉘는지는 `StaySearchServiceTest.splitsBySupplierLimit` 이 상한 2 짜리 스텁으로 확인한다.

---

## 공통으로 받는 것 — 건드리지 않는다

| 도구 | 해주는 것 |
| --- | --- |
| `SupplierWebClients.create` | 연결 타임아웃(Netty) · 응답 제한 · `X-Api-Key` · `Accept` 헤더 |
| `HttpFailures.fromThrowable` / `fromStatusCode` | 전송 계층 실패 → `FailureReason` |
| `HttpFailures.describe` | 로그·응답에 남길 짧은 설명 (**공급사 원문을 흘리지 않는다**) |
| `SupplierHttpProperties.requireUsableBatchSize` | 묶음 상한 검증 |

여기 있는 것은 **어느 공급사에게나 똑같이 생기는 일**이다. 특정 공급사 사정이 이 안에 들어가면
경계가 잘못 그어진 것이다.

---

## 빠뜨리기 쉬운 것

B 를 붙이며 실제로 걸렸거나, 걸릴 뻔한 것들이다.

**① 본문 코드를 보기 전에 옮기면 장애가 "빈 성공"이 된다.**
장애 응답은 대개 `data: null` 이다. 코드를 안 보고 매퍼에 넘기면 빈 목록이 되어 **성공했는데 결과가
없다**로 나간다. 실패가 조용히 사라지는 자리라 순서를 지켜야 한다.

**② 상태 코드 판정을 대체하지 말고 얹는다.**
본문 코드로 알리는 공급사도 연결 실패·타임아웃·인프라 5xx 는 상태 코드로 온다. 두 층이 다 필요하다.

**③ 항목 하나 때문에 묶음 전체를 실패시키지 않는다.**
매퍼가 항목 단위로 예외를 잡아 그 건만 건너뛴다. 안 잡으면 `onErrorResume` 이 받아서 그 묶음이
통째로 실패가 된다.

**④ 본문이 비어 오면 값 없이 끝난다.**
`bodyToMono` 는 빈 본문에서 아무것도 내보내지 않아 `map` 이 불리지 않는다. 그대로 두면 "어떤
경우에도 결과를 돌려준다"는 포트 계약이 그 경우에 깨진다. `defaultIfEmpty` 로 막는다.
(A 어댑터는 아직 이 처리가 없다 — JOURNAL 열린 문제 7)

**⑤ 공급사 코드는 나중에 바꿀 수 없다.**
`supplier()` 값은 매핑 테이블의 unique 키 일부다(`(supplier, supplier_property_code)`). 나중에
바꾸면 기존 매핑이 전부 안 잡히고 새 내부 식별자가 발급된다. **"같은 공급사 상품은 언제 조회해도
같은 내부 식별자"** 라는 이 시스템의 유일한 보장이 그 순간 깨진다.

**⑥ 코드 길이 제약이 스키마에 있다.**
`supplier` 는 16자, 숙소·객실 타입 코드는 64자까지다([schema.sql](../src/main/resources/schema.sql)).
`SupplierCode` 는 길이를 검사하지 않으므로, 넘치면 생성이 아니라 **저장할 때** 깨진다.

**⑦ 응답 스키마가 늘어난다면 그건 신호다.**
새 공급사 때문에 `api` 의 응답 타입을 고쳐야 한다면, 표준 모델이 그 공급사의 표현을 흡수하지
못한 것이다. 어댑터에서 흡수할 수 있는지 먼저 본다.
