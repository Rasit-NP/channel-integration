package com.channel.integration.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 하루치 요금 내역. 날짜별 요금을 주는 공급사에서만 만들어진다.
 *
 * <p>세금을 따로 주는 공급사를 기준으로 net/tax 를 나눠 들고, 고객이 실제로 내는 금액은
 * {@link #grossAmount()} 다.
 */
public record NightlyRate(LocalDate date, Money netAmount, Money taxAmount) {

    public NightlyRate {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(netAmount, "netAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
    }

    /** 그날 고객 결제 금액 = 세금 별도 요금 + 세금. */
    public Money grossAmount() {
        return netAmount.plus(taxAmount);
    }
}
