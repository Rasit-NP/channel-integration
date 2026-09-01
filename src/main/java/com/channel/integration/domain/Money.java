package com.channel.integration.domain;

import java.util.Objects;

/**
 * 금액. 통화의 최소 단위 정수로 다룬다(KRW 는 원 단위).
 *
 * <p>실수 타입을 쓰지 않는 이유는 합산 과정에서 오차가 생기기 때문이다. 공급사가 정수로 주므로
 * 정수로 유지한다.
 */
public record Money(long amount, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase();
        if (currency.length() != 3) {
            throw new IllegalArgumentException("통화는 ISO 4217 3자리여야 한다: " + currency);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없다: " + amount);
        }
    }

    public static Money of(long amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!this.currency.equals(other.currency)) {
            // 통화가 다른 금액을 더하는 것은 값이 아니라 의미가 깨지는 일이므로 막는다.
            throw new IllegalArgumentException(
                    "통화가 다르면 합산할 수 없다: %s vs %s".formatted(this.currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
