package com.channel.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StayPriceTest {

    /** 09-01 체크인 / 09-04 체크아웃 = 3박 (09-01, 09-02, 09-03). */
    private static final DateRange THREE_NIGHTS =
            DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

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
                    rate("2026-09-03", 120_000, 12_000)), THREE_NIGHTS);

            // (120000+12000) + (150000+15000) + (120000+12000)
            assertThat(price.totalAmount()).isEqualTo(Money.of(429_000, "KRW"));
        }

        @Test
        @DisplayName("세액도 합산해 함께 보관한다")
        void keepsTaxAmount() {
            StayPrice price = StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 120_000, 12_000),
                    rate("2026-09-02", 150_000, 15_000),
                    rate("2026-09-03", 120_000, 12_000)), THREE_NIGHTS);

            assertThat(price.tax()).contains(Money.of(39_000, "KRW"));
        }

        @Test
        @DisplayName("원본 날짜별 내역을 버리지 않는다")
        void keepsBreakdown() {
            StayPrice price = StayPrice.fromNightlyRates(
                    List.of(rate("2026-09-01", 88_000, 8_800), rate("2026-09-02", 99_000, 9_900)),
                    DateRange.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03")));

            assertThat(price.hasNightlyBreakdown()).isTrue();
            assertThat(price.nightlyRates()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("요금 날짜가 요청 기간과 어긋날 때")
    class AgainstRequestedDates {

        @Test
        @DisplayName("요청 기간 밖의 날짜는 총액에 넣지 않는다 — 공급사가 얹어 보내도 부풀지 않는다")
        void ignoresDatesOutsideRange() {
            // 3박을 물었는데 공급사가 09-04 까지 네 날을 줬다.
            StayPrice price = StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 100_000, 10_000),
                    rate("2026-09-02", 100_000, 10_000),
                    rate("2026-09-03", 100_000, 10_000),
                    rate("2026-09-04", 100_000, 10_000)), THREE_NIGHTS);

            assertThat(price.totalAmount()).isEqualTo(Money.of(330_000, "KRW"));
            assertThat(price.tax()).contains(Money.of(30_000, "KRW"));

            // 내역에도 남기지 않는다. sum(nightlyRates) == totalAmount 가 유지되어야 한다.
            assertThat(price.nightlyRates()).hasSize(3);
            assertThat(price.nightlyRates().stream()
                    .mapToLong(rate -> rate.grossAmount().amount()).sum())
                    .isEqualTo(price.totalAmount().amount());
        }

        @Test
        @DisplayName("숙박일 하나라도 요금이 없으면 총액을 만들 수 없다 — 있는 것만 더하면 과소 청구다")
        void rejectsMissingNight() {
            assertThatThrownBy(() -> StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 100_000, 10_000),
                    rate("2026-09-03", 100_000, 10_000)), THREE_NIGHTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("숙박 3일");
        }

        @Test
        @DisplayName("같은 숙박일이 두 번 오면 총액을 만들 수 없다 — 그냥 더하면 하루치를 두 번 청구한다")
        void rejectsDuplicateNight() {
            assertThatThrownBy(() -> StayPrice.fromNightlyRates(List.of(
                    rate("2026-09-01", 100_000, 10_000),
                    rate("2026-09-01", 120_000, 12_000),
                    rate("2026-09-02", 100_000, 10_000),
                    rate("2026-09-03", 100_000, 10_000)), THREE_NIGHTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2026-09-01");
        }

        @Test
        @DisplayName("요청 기간과 겹치는 날짜가 하나도 없으면 총액을 만들 수 없다")
        void rejectsWhenNothingOverlaps() {
            assertThatThrownBy(() -> StayPrice.fromNightlyRates(List.of(
                    rate("2026-10-01", 100_000, 10_000)), THREE_NIGHTS))
                    .isInstanceOf(IllegalArgumentException.class);
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
                rate("2026-09-03", 120_000, 12_000)), THREE_NIGHTS);
        StayPrice fromB = StayPrice.fromTotal(Money.of(452_000, "KRW"));

        assertThat(fromA.currency()).isEqualTo(fromB.currency());
        assertThat(fromA.totalAmount().amount()).isLessThan(fromB.totalAmount().amount());

        // 다만 비교 가능하다는 것이 같은 조건이라는 뜻은 아니다.
        // 조식 포함 여부 같은 조건 차이는 SupplierOffer 에서 따로 들고 함께 노출한다.
    }
}
