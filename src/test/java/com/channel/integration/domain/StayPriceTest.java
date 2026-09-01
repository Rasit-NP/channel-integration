package com.channel.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StayPriceTest {

    private static NightlyRate rate(String date, long net, long tax) {
        return new NightlyRate(LocalDate.parse(date), Money.of(net, "KRW"), Money.of(tax, "KRW"));
    }

    @Nested
    @DisplayName("날짜별 단가를 주는 공급사")
    class FromNightlyRates {

        @Test
        @DisplayName("각 날짜의 (세금 별도 + 세금)을 합쳐 세금 포함 총액을 만든다")
        void sumsGrossAmounts() {
            StayPrice price = StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 120_000, 12_000),
                    rate("2026-09-02", 150_000, 15_000),
                    rate("2026-09-03", 120_000, 12_000)));

            // (120000+12000) + (150000+15000) + (120000+12000)
            assertThat(price.totalAmount()).isEqualTo(Money.of(429_000, "KRW"));
        }

        @Test
        @DisplayName("세액도 합산해 함께 보관한다")
        void keepsTaxAmount() {
            StayPrice price = StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 120_000, 12_000),
                    rate("2026-09-02", 150_000, 15_000),
                    rate("2026-09-03", 120_000, 12_000)));

            assertThat(price.tax()).contains(Money.of(39_000, "KRW"));
        }

        @Test
        @DisplayName("원본 날짜별 내역을 버리지 않는다")
        void keepsBreakdown() {
            StayPrice price = StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 88_000, 8_800),
                    rate("2026-09-02", 99_000, 9_900)));

            assertThat(price.hasNightlyBreakdown()).isTrue();
            assertThat(price.nightlyRates()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("기간 총액만 주는 공급사")
    class FromTotal {

        @Test
        @DisplayName("총액을 그대로 표준 총액으로 삼는다")
        void usesTotalAsIs() {
            StayPrice price = StayPrice.fromTotal(Money.of(452_000, "KRW"));

            assertThat(price.totalAmount()).isEqualTo(Money.of(452_000, "KRW"));
        }

        @Test
        @DisplayName("날짜별 요금을 지어내지 않는다 — 총액을 날짜 수로 나누지 않는다")
        void doesNotFabricateNightlyRates() {
            StayPrice price = StayPrice.fromTotal(Money.of(452_000, "KRW"));

            assertThat(price.hasNightlyBreakdown()).isFalse();
            assertThat(price.nightlyRates()).isEmpty();
        }

        @Test
        @DisplayName("세액은 알 수 없으므로 비어 있다")
        void hasNoTaxAmount() {
            StayPrice price = StayPrice.fromTotal(Money.of(452_000, "KRW"));

            assertThat(price.tax()).isEmpty();
        }
    }

    @Test
    @DisplayName("두 공급사의 요금이 같은 형태(세금 포함 총액)로 비교 가능해진다")
    void bothSuppliersBecomeComparable() {
        StayPrice fromA = StayPrice.fromNightlyRates(List.of(
                rate("2026-09-01", 120_000, 12_000),
                rate("2026-09-02", 150_000, 15_000),
                rate("2026-09-03", 120_000, 12_000)));
        StayPrice fromB = StayPrice.fromTotal(Money.of(452_000, "KRW"));

        assertThat(fromA.currency()).isEqualTo(fromB.currency());
        assertThat(fromA.totalAmount().amount()).isLessThan(fromB.totalAmount().amount());

        // 다만 비교 가능하다는 것이 같은 조건이라는 뜻은 아니다.
        // 조식 포함 여부 같은 조건 차이는 SupplierOffer 에서 따로 들고 함께 노출한다.
    }
}
