# 연동 지표와 모니터링

공급사별 **성공률·응답 지연·타임아웃 비율**을 무엇으로 재고, 어디에 붙이고, 어떤 라벨로 나눌지를
적는다. **왜 이런 값을 보는지는 [README §연동 견고성](../README.md) 에 있다.**

> **이 문서는 설계이고 구현이 없다.** 다른 문서(`adding-supplier.md`·`integration-model.md`)는
> 실제로 밟은 뒤에 썼지만 이건 아니다. 그래서 **현재 상태는 전부 실측으로 확인하고, 앞으로의
> 모양만 설계로 적는다.** 표기를 나눈다 — ✅ 실측, ○ 설계.
>
> 확인해 보니 설계보다 **현재 상태 쪽에서 나온 것이 많았다.** §검증에서 드러난 것 이 그 자리다.

---

## ✅ 지금 관측할 수 있는 것

| 무엇 | 어디에 남나 | 한계 |
| --- | --- | --- |
| 공급사 조회 **실패** | `log.warn` — 두 어댑터의 `failure(...)` 한 곳으로 모인다 | 로그다. 세려면 파싱해야 한다 |
| 공급사 조회 **성공** | **아무 데도 안 남는다** | 성공률의 분모가 없다 |
| **응답 지연** | **아무 데도 안 남는다** | 시간을 재는 코드가 운영 코드에 하나도 없다 |
| 동기화 결과 | `SyncReport` → `POST /internal/suppliers/sync` 응답 + `log.info` | 그 호출의 결과일 뿐 누적이 아니다 |
| 검색 제외 건수 | `StaySearchResult` → 응답 세 필드 | 응답 한 건의 값이지 누적이 아니다 |
| 공급사별 실패 사유 | 검색·동기화 응답의 `reason` | 응답을 받은 쪽만 안다 |

**결론: 지금 셀 수 있는 것은 실패 건수뿐이다.** 이 문서가 다루는 세 지표 중 성공률은 분모가 없고,
응답 지연은 원천 데이터가 아예 없으며, 타임아웃 비율은 §검증에서 드러난 것 ①의 이유로 값이
틀린다.

다만 **실패 로그가 이미 지표의 축을 갖고 있다.**

```
공급사 {supplier} {operation} 조회 실패: reason={reason} detail={detail}
```

`(supplier, operation, reason)` 세 축은 아래 §라벨 설계 가 쓰려는 것과 정확히 같다. 없는 것은
축이 아니라 **성공 쪽 절반과 시간**이다.

### ✅ 클래스패스 상태

| 항목 | 있나 |
| --- | --- |
| `micrometer-observation` | **있다** (1.15.12) — `spring-context`·`spring-web` 이 끌고 온다 |
| `micrometer-core` (`MeterRegistry`) | **없다** |
| `spring-boot-starter-actuator` | **없다** |

Observation API 는 클래스패스에 있지만 **기록할 레지스트리가 없다.** 지표를 실제로 남기려면
의존성이 하나 늘어난다(§붙이는 방법).

---

## ○ 무엇을 재는가

이름은 Micrometer 관례(점 구분)를 따른다.

| 지표 | 타입 | 라벨 | 답하는 질문 |
| --- | --- | --- | --- |
| `supplier.fetch` | Timer | `supplier`, `operation`, `outcome` | 그 공급사가 **얼마나 걸리나** |
| `supplier.fetch.failure` | Counter | `supplier`, `operation`, `reason` | **왜** 실패했나 |
| `search.duration` | Timer | `partial` | 검색 한 건이 얼마나 걸리나 |
| `search.excluded` | Counter | `cause` | 결과에서 무엇이 왜 빠졌나 |
| `supplier.offer.dropped` | Counter | `supplier`, `stage` | 어댑터가 못 옮겨 버린 수 (열린 문제 4) |
| `sync.applied` | Counter | `supplier`, `kind` | 매핑이 얼마나 반영됐나 |

`outcome` 은 `success` \| `failure`. `operation` 은 `properties` \| `offers` — 포트가 선언한
두 조회에 그대로 대응한다. `cause` 는 응답의 제외 건수 셋(`sold_out`·`unmapped`·`over_capacity`),
`kind` 는 `property` \| `room_type`.

### 파생 지표 — 성공률 · 응답 지연 · 타임아웃 비율

| 지표 | 계산식 |
| --- | --- |
| 성공률 | `supplier.fetch{outcome=success} / supplier.fetch` — 공급사·작업별 |
| 응답 지연 | `supplier.fetch` 의 p50·p95·p99 — 평균은 보지 않는다 |
| 타임아웃 비율 | `supplier.fetch.failure{reason=TIMEOUT} / supplier.fetch` — **단, ① 을 고친 뒤** |

성공률과 타임아웃 비율의 분모가 같은 `supplier.fetch` 라는 점이 중요하다. **분모를 따로 세면
두 비율이 서로 안 맞는 순간이 온다.**

---

## ○ 라벨 설계

지표의 라벨은 **값의 가짓수가 미리 정해져 있어야 한다.** 시계열 수가 라벨 값의 곱으로 늘기
때문이다.

| 라벨 | 가짓수 | 근거 |
| --- | --- | --- |
| `supplier` | 공급사 수 (현재 2) | 어댑터 구현체 수만큼 |
| `operation` | 2 | `SupplierAdapter` 가 선언한 조회가 둘 |
| `outcome` | 2 | 성공·실패 |
| `reason` | 7 | `FailureReason` 값 수 |

현재 시계열 상한: `supplier.fetch` 는 2×2×2 = **8**, `supplier.fetch.failure` 는 2×2×7 = **28**.
공급사가 하나 늘 때마다 비례해서만 는다.

**라벨에 넣지 않는 것.**

| 안 넣는 것 | 이유 |
| --- | --- |
| 숙소 코드 · 객실 타입 코드 | 상한이 없다. 숙소가 수천 개면 시계열이 수천 개가 된다 |
| 체크인·체크아웃 날짜 | 날짜마다 새 시계열이 생기고 영원히 안 줄어든다 |
| `detail` 문자열 | 예외 메시지가 섞여 들어온다. 로그의 몫이다 |
| 묶음 번호 | 요청마다 달라진다 |

**지표는 "몇 건이 어떤 종류로 일어났나"까지만 답한다.** 어느 숙소였는지는 로그가 답한다.

---

## ○ 측정 지점

| 지표 | 붙는 자리 |
| --- | --- |
| `supplier.fetch`, `supplier.fetch.failure` | `SupplierAdapter` 를 감싸는 **데코레이터** |
| `search.duration`, `search.excluded` | `StaySearchService.search()` 의 반환 직전 |
| `sync.applied` | `PropertySyncService.apply()` |
| `supplier.offer.dropped` | 매퍼가 건너뛴 수를 세어 올린 뒤 어댑터에서 (열린 문제 4 와 함께) |

### 왜 어댑터 안이 아니라 데코레이터인가

어댑터 안에 계측을 넣으면 **새 공급사를 붙일 때마다 같은 코드를 되풀이**해야 하고, 빠뜨려도
아무것도 잡아주지 않는다. `adding-supplier.md` 의 절차에 항목이 하나 늘어난다는 뜻이기도 하다.

두 서비스가 `List<SupplierAdapter>` 를 주입받으므로, 등록 시점에 감싸면 **공급사별 코드가 늘지
않는다.**

```java
// adapter.support — 어느 공급사에게나 똑같이 생기는 일이 있는 자리
final class MeteredSupplierAdapter implements SupplierAdapter {
    // supplier() · maxBatchSize() 는 그대로 위임한다.
    // 두 조회만 Timer 로 감싸고, 결과가 Success 인지 Failure 인지로 outcome 을 정한다.
}
```

`SupplierFetchResult` 가 **성공과 실패를 모두 값으로** 돌려주기 때문에 이것이 가능하다. 실패가
예외로 나갔다면 데코레이터가 `onErrorResume` 으로 다시 값으로 되돌려야 했다.

### 지연은 왜 서비스에서 못 재나

`StaySearchService.assemble()` 은 **모든 묶음 결과가 지나가는 자리**라 성공·실패를 세기에는
맞다. 하지만 그때는 이미 `collectList()` 로 다 모인 뒤라 **각 호출이 얼마나 걸렸는지가 사라져
있다.** 시간은 `Mono` 를 만드는 자리에서 재야 한다.

---

## ○ 무엇을 하나로 세는가 — 묶음과 공급사

**실패의 실제 단위는 (공급사 × 묶음)이고, 고객에게 노출하는 단위는 공급사다**(README §검색 전체에도
상한을 둔다). 지표는 어느 쪽을 따라야 하는가.

| | 세는 단위 | 이유 |
| --- | --- | --- |
| `supplier.fetch` | **묶음** | 호출 한 번이 한 건이다. 지연도 묶음 단위로 생긴다 |
| `search.duration` | 검색 요청 | 고객이 기다린 시간은 요청 하나당 하나다 |
| 응답의 `suppliers[].status` | 공급사 | 이미 그렇게 나간다. 지표가 따라갈 이유가 없다 |

묶음으로 세면 **"공급사는 실패로 표시됐는데 묶음 4개 중 1개만 실패였다"** 가 보인다. 공급사
단위로만 세면 그 넷이 하나로 뭉개져, 전면 장애와 산발적 실패가 같은 수로 나온다.

---

## 검증에서 드러난 것

이 문서를 쓰면서 확인한 것들이다. **지표를 지금 정의대로 붙이면 틀린 값이 나오는 자리**다.

### ✅ ① 연결 타임아웃이 `TIMEOUT` 으로 안 잡힌다

닿지 않는 주소로 실제 호출해 확인했다.

```
예외 타입 = WebClientRequestException
  cause: io.netty.channel.ConnectTimeoutException / connection timed out after 1000 ms
걸린 시간 = 1104ms
분류      = SUPPLIER_ERROR      ← TIMEOUT 이 아니다
```

`HttpFailures.hasTimeoutCause` 는 `java.util.concurrent.TimeoutException` 과
`io.netty.handler.timeout.ReadTimeoutException` 둘만 본다. 그런데 `ConnectTimeoutException` 은
**둘 중 어느 것도 아니다** — `java.net.ConnectException` 을 상속한다. 컴파일러가 먼저 알려줬다
(`ConnectTimeoutException cannot be converted to TimeoutException`).

| 무엇이 끊었나 | 예외 | 현재 분류 | 맞는 분류 |
| --- | --- | --- | --- |
| 응답 제한 (Reactor `timeout()`) | `TimeoutException` | `TIMEOUT` | `TIMEOUT` |
| 읽기 제한 (Netty) | `ReadTimeoutException` | `TIMEOUT` | `TIMEOUT` |
| **연결 제한 (Netty)** | `ConnectTimeoutException` | **`SUPPLIER_ERROR`** | `TIMEOUT` |

**이게 지표에 왜 문제인가.** 타임아웃 비율을 `reason=TIMEOUT` 으로 세면 **연결 타임아웃이 통째로
빠지고**, 그만큼 `SUPPLIER_ERROR` 가 부풀려진다. `api.md` 는 `SUPPLIER_ERROR` 를 "공급사가 자기 쪽
오류를 알림"으로 정의했는데, 연결 타임아웃은 **공급사가 아무것도 알린 적이 없는** 경우다.

증상도 정반대로 읽힌다. 공급사가 죽어 응답이 없는 것은 우리가 네트워크 경로를 의심할 일인데,
"공급사가 자기 오류를 알렸다"로 집계되면 **공급사에 문의하게 된다.**

그리고 README §타임아웃 은 연결 제한 2초를 **근거를 들어 정한 값**으로 내세운다. 지금은 그 값이
동작한 결과가 **그것을 보려고 만든 지표에 나타나지 않는다.**

현재 테스트는 응답 타임아웃(무응답 모드)만 확인하고 **연결 타임아웃 분류를 확인하는 테스트가
없다.** 그래서 이 구멍이 남아 있었다.

### ✅ ② `TIMEOUT` 하나에 성격이 다른 두 사건이 섞인다

| 사건 | 어디서 | 단위 | 대응 |
| --- | --- | --- | --- |
| 묶음 하나가 응답 제한을 넘김 | 어댑터 `.timeout(responseTimeout)` | 묶음 | 공급사가 느리다 |
| 묶음이 검색 상한까지 안 옴 | `StaySearchService.markMissingAsTimedOut` | 공급사 | **우리 상한이 짧거나 묶음이 많다** |

둘 다 `FailureReason.TIMEOUT` 이고, `detail` 문자열(`"%d/%d 묶음 미도착"`)로만 갈린다. 그런데
`detail` 은 라벨에 넣지 않기로 했으므로(§라벨 설계) **지표에서는 구분되지 않는다.**

고쳐야 할 대상이 다르다 — 앞은 공급사 쪽이고, 뒤는 우리 설정이다. 지표를 붙일 때
`supplier.fetch.failure` 에 `TIMEOUT` 을 두 갈래로 나누거나, 뒤쪽만 별도 카운터로 센다.

### ✅ ③ 어댑터가 버린 항목은 어느 카운터에도 없다

`integration-model.md §상품이 결과에서 빠지는 모든 경로` 에 이미 적힌 알려진 구멍이고, 열린 문제
4 다. 지표 관점에서 덧붙일 것은 **이게 조용한 손실**이라는 점이다 — 공급사가 응답 형식을 바꾸면
결과가 줄어드는데 실패로도 안 잡히고 제외 건수로도 안 잡힌다. `supplier.offer.dropped` 가 그
자리다.

---

## ○ 타임아웃 값을 다시 잡는 절차

README §타임아웃 이 "관측하면 p99 기준으로 다시 잡아야 한다"고 약속한 것의 실제 순서다.

| 순서 | 할 일 |
| --- | --- |
| 1 | `supplier.fetch` 히스토그램을 공급사·작업별로 최소 한 주기 모은다 |
| 2 | 공급사별 p99 를 본다. **평균이 아니다** — 가장 느린 공급사가 전체를 결정한다 |
| 3 | 응답 제한 = p99 × 여유. 정상 응답을 잘라내면 없어도 될 실패를 만든다 |
| 4 | 아래 부등식이 유지되는지 확인한다 |
| 5 | 바꾼 뒤 타임아웃 비율과 성공률을 함께 본다. 한쪽만 보면 제한을 늘려 실패를 숨길 수 있다 |

### ✅ 상한들 사이의 관계

세 상한은 독립이 아니다.

```
connect(2s)  <  response(3s)  <  search(5s)
```

한 공급사의 최악 소요는 **묶음 수와 동시 호출 상한**이 정한다.

```
공급사 최악 ≈ ceil(묶음 수 / max-concurrency) × response-timeout
```

현재 기본값(`response=3s`, `max-concurrency=4`, `max-batch-size=50`)에 넣으면:

| 숙소 수 | 묶음 | 파동 | 최악 소요 | 검색 상한(5s) |
| --- | --- | --- | --- | --- |
| ~200 | 4 | 1 | 3s | 여유 있다 |
| 201~250 | 5 | 2 | **6s** | **넘는다** |

**숙소가 200개를 넘는 순간부터 검색 전체 상한에 걸려 부분 실패가 나기 시작한다.** 지금 데이터로는
걸리지 않지만(숙소 3개), 이 문턱은 설정만으로 정해지는 값이라 지표 없이도 계산된다. 넘기 시작하면
`max-concurrency` 를 올리거나 검색 상한을 늘려야 하고, 그 판단의 근거가 §무엇을 재는가 의
`search.duration` 이다.

이건 README §숙소가 수천 개가 되면 이 말한 한계가 **어디서부터 시작되는지의 구체적인 수**다.

---

## ○ 경보 기준

세는 것과 사람을 부르는 것은 다르다. **재시도로 해결되지 않는 것**을 먼저 부른다.

| 조건 | 왜 | 누가 |
| --- | --- | --- |
| `reason=UNAUTHORIZED` 가 1건이라도 | 키 설정 문제다. 재시도는 무의미하고 저절로 낫지 않는다 | 우리 |
| `excludedUnmapped` 가 계속 > 0 | 동기화가 밀렸다. **우리 문제**다 (README §무엇을 왜 뺐는지) | 우리 |
| 특정 공급사 성공률이 기준선 아래로 | 그 공급사 장애 | 공급사 |
| `reason=RATE_LIMITED` 증가 | 우리가 너무 많이 부른다. `max-concurrency` 를 본다 | 우리 |
| `partial=true` 비율 상승 | 고객이 불완전한 결과를 받고 있다 | 우리 |
| 동기화가 연속 실패 | 매핑이 낡아간다. 검색은 아직 도니 **조용히 나빠진다** | 우리 |

마지막 줄이 이 목록에서 가장 눈에 안 띄는 항목이다. 동기화 실패는 검색을 멈추지 않도록 설계했고
(README §숙소 목록을 언제 동기화하는가), 그래서 **아무도 모르는 채로 오래 갈 수 있다.**

---

## ○ 붙이는 방법

| | 늘어나는 것 |
| --- | --- |
| 의존성 | `spring-boot-starter-actuator` (`MeterRegistry` 가 여기 딸려 온다) |
| 노출 | `/actuator/prometheus` — `/internal/**` 과 같이 **고객 API 와 경로를 나눈다** |
| 코드 | `adapter.support` 에 데코레이터 하나, 두 서비스에 카운터 몇 줄 |

| | 안 바뀌는 것 |
| --- | --- |
| `domain` | 지표는 도메인 규칙이 아니다 |
| `port` | `SupplierAdapter` 시그니처가 그대로여야 데코레이터가 성립한다 |
| 각 어댑터 (`adapter.a`·`adapter.b`) | 계측이 데코레이터에 있으므로 |

**공급사를 추가할 때 지표 때문에 고칠 곳이 없어야 한다.** 그것이 데코레이터를 고른 이유고,
`adding-supplier.md` 의 절차에 항목이 늘지 않는다는 뜻이다.

---

## 범위 밖

| 안 다루는 것 | 이유 |
| --- | --- |
| 분산 추적(trace) | 프로세스가 하나다. 지금 얻을 것이 없다 |
| 로그 수집·저장 파이프라인 | 이 저장소의 코드가 아니라 배포 환경의 몫이다 |
| 대시보드·경보 도구 선택 | 지표 이름과 라벨이 정해지면 도구는 갈아끼울 수 있다 |
| 비용·매출 지표 | 연동 견고성과 다른 주제다 |

---

## 이 문서가 남긴 할 일

구현이 없으므로 여기서 끝나지 않는다. 확인된 것 중 **코드를 고쳐야 하는 것**은 아래다.

| # | 무엇 | 근거 |
| --- | --- | --- |
| 1 | 연결 타임아웃을 `TIMEOUT` 으로 분류 + 그것을 고정하는 테스트 | §검증에서 드러난 것 ① |
| 2 | `TIMEOUT` 두 갈래를 지표에서 구분 | ② |
| 3 | 어댑터가 버린 항목 세기 | ③ · 열린 문제 4 |

1 은 지표 이전에 **응답의 `reason` 값이 지금 틀리다는 뜻**이라, 지표를 붙이지 않더라도 고칠
값어치가 있다.
