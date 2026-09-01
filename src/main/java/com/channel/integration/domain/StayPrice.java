package com.channel.integration.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 표준 요금. <b>숙박 기간 전체 총액, 세금 포함(gross)</b>이 기준이다.
 *
 * <p>공급사마다 요금 표현이 다르지만 변환은 한 방향으로만 가능하다. 날짜별 (세금 별도 + 세금)을
 * 합치면 세금 포함 총액이 정확히 나오는 반면, 총액만 주는 공급사의 값을 날짜별로 쪼개거나 세액을
 * 분리하는 것은 불가능하다. 그래서 두 공급사가 모두 정확히 표현할 수 있는 총액을 표준으로 삼고,
 * 날짜별 내역과 세액은 <b>제공하는 공급사만 채우는 선택 정보</b>로 둔다.
 *
 * <p>정규화 규칙을 바꾸려면 이 클래스의 두 팩토리만 고치면 된다. 어댑터는 자기에게 맞는 팩토리를
 * 고르기만 한다.
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
     */
    public static StayPrice fromNightlyRates(List<NightlyRate> rates) {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException("날짜별 요금이 비어 있으면 총액을 만들 수 없다");
        }
        String currency = rates.getFirst().netAmount().currency();
        Money total = Money.zero(currency);
        Money tax = Money.zero(currency);
        for (NightlyRate rate : rates) {
            total = total.plus(rate.grossAmount());
            tax = tax.plus(rate.taxAmount());
        }
        return new StayPrice(total, rates, tax);
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
