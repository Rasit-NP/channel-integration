package com.channel.integration.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 표준 요금. <b>숙박 기간 전체 총액, 세금 포함(gross)</b>이 기준이다.
 *
 * <p>공급사마다 요금 표현이 다르지만 변환은 한 방향으로만 가능하다. 날짜별 (세금 별도 + 세금)을
 * 합치면 세금 포함 총액이 정확히 나오는 반면, 총액만 주는 공급사의 값을 날짜별로 쪼개거나 세액을
 * 분리하는 것은 불가능하다. 그래서 두 공급사가 모두 정확히 표현할 수 있는 총액을 표준으로 삼고,
 * 날짜별 내역과 세액은 <b>제공하는 공급사만 채우는 선택 정보</b>로 둔다.
 *
 * <p>정규화 규칙을 바꾸려면 이 클래스의 두 팩토리만 고치면 된다. 어댑터는 자기에게 맞는 팩토리를
 * 고르고, 공급사가 준 것과 요청 기간을 넘기기만 한다. <b>기간에 맞춰 무엇을 세고 무엇을 버릴지는
 * 여기서 정한다</b> — 공급사마다 되풀이할 규칙으로 두면 새 어댑터가 빠뜨려도 아무것도 잡아주지
 * 않는다.
 */
public record StayPrice(Money totalAmount, List<NightlyRate> nightlyRates, Money taxAmount) {

    public StayPrice {
        Objects.requireNonNull(totalAmount, "totalAmount");
        nightlyRates = nightlyRates == null ? List.of() : List.copyOf(nightlyRates);
        // taxAmount 는 null 을 허용한다. 세액을 주지 않는 공급사가 있기 때문이다.
    }

    /**
     * 날짜별 요금을 주는 공급사용. 각 날짜의 (세금 별도 + 세금)을 합쳐 총액을 만들고,
     * 세액도 합산해 함께 보관한다. 원본 내역을 버리지 않는다.
     *
     * <p><b>요청 기간을 받는 이유는 총액이 기간에 의존하는 값이기 때문이다.</b> 공급사가 어떤
     * 날짜를 줬는지와 무관하게, 총액은 "요청한 숙박일의 요금을 합친 것"이어야 한다. 이 규칙이
     * 여기 없으면 어댑터마다 같은 규칙을 기억해야 하고, 잊어도 아무것도 잡아주지 않는다.
     * 재고를 {@link Availability#forStay} 가 기간과 함께 판정하는 것과 같은 모양이다.
     *
     * <p>기간과 어긋나는 두 방향을 다르게 다룬다.
     * <ul>
     *   <li><b>넘치면 버린다</b> — 요청 기간 밖의 날짜는 이 숙박의 결제 대상이 아니다. 버려도
     *       잃는 것이 없다.</li>
     *   <li><b>모자라면 거부한다</b> — 숙박일 하나라도 요금이 없으면 그 기간의 총액을 만들 수
     *       없다. 있는 것만 더하면 실제 결제액보다 적은 금액을 내보내게 되고, 채우려면 없는 값을
     *       지어내야 한다. 정보를 잃는 것보다 지어내는 것이 나쁘다.</li>
     * </ul>
     *
     * <p>같은 숙박일이 두 번 오는 것도 거부한다. 어느 쪽이 맞는지 알 수 없는데 그냥 더하면
     * 하루치를 두 번 청구한다.
     *
     * @throws IllegalArgumentException 요금 날짜가 요청 기간의 숙박일과 정확히 대응하지 않을 때
     */
    public static StayPrice fromNightlyRates(List<NightlyRate> rates, DateRange dates) {
        Objects.requireNonNull(dates, "dates");
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException("날짜별 요금이 비어 있으면 총액을 만들 수 없다");
        }

        List<NightlyRate> covered = new ArrayList<>();
        Set<LocalDate> seen = new LinkedHashSet<>();
        for (NightlyRate rate : rates) {
            if (!dates.covers(rate.date())) {
                continue; // 요청 기간 밖의 날짜는 결제 대상이 아니다.
            }
            if (!seen.add(rate.date())) {
                throw new IllegalArgumentException("같은 숙박일의 요금이 두 번 왔다: " + rate.date());
            }
            covered.add(rate);
        }

        if (covered.size() != dates.nights()) {
            throw new IllegalArgumentException(
                    "요청 기간의 모든 숙박일에 요금이 있어야 총액을 만들 수 있다: 숙박 %d일, 요금 %d일"
                            .formatted(dates.nights(), covered.size()));
        }

        String currency = covered.getFirst().netAmount().currency();
        Money total = Money.zero(currency);
        Money tax = Money.zero(currency);
        for (NightlyRate rate : covered) {
            total = total.plus(rate.grossAmount());
            tax = tax.plus(rate.taxAmount());
        }
        // 버린 날짜는 내역에도 남기지 않는다. sum(nightlyRates) == totalAmount 가 유지되어야 한다.
        return new StayPrice(total, covered, tax);
    }

    /**
     * 기간 총액만 주는 공급사용. 날짜별 내역과 세액은 알 수 없으므로 비운다.
     *
     * <p>총액을 날짜 수로 나누어 날짜별 요금을 만들지 않는다. 없는 정보를 지어내는 것이고,
     * 그 값이 고객에게 노출되면 실제 결제액과 어긋난다.
     */
    public static StayPrice fromTotal(Money total) {
        return new StayPrice(total, List.of(), null);
    }

    /** 날짜별 내역을 가진 요금인가. 부분 취소 환불액 계산 등이 가능한지의 판단 기준. */
    public boolean hasNightlyBreakdown() {
        return !nightlyRates.isEmpty();
    }

    /** 세액. 주지 않는 공급사가 있으므로 없을 수 있다. */
    public Optional<Money> tax() {
        return Optional.ofNullable(taxAmount);
    }

    public String currency() {
        return totalAmount.currency();
    }
}
