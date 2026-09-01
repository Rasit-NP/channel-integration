package com.channel.integration.domain;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청 기간 전체를 예약할 수 있는 객실 수.
 *
 * <p>연박은 같은 객실 타입을 <b>연속으로</b> 확보해야 하므로 <b>병목이 되는 하루가 전체를
 * 결정</b>한다. 그래서 날짜별 잔여 수의 최솟값을 취한다. 합계나 평균으로 판정하면 중간에 0 인
 * 날이 있어도 예약 가능으로 노출하게 된다.
 *
 * <p>판정 규칙을 바꾸려면 {@link #forStay} 하나만 고치면 된다.
 */
public record Availability(int availableRooms) {

    public Availability {
        if (availableRooms < 0) {
            throw new IllegalArgumentException("예약 가능 객실 수는 음수일 수 없다: " + availableRooms);
        }
    }

    /**
     * @param inventories 공급사가 준 날짜별 잔여 수. 순서·중복·누락을 신뢰하지 않는다.
     * @param dates       요청 기간. 이 기간의 모든 숙박일에 재고가 있어야 예약할 수 있다.
     */
    public static Availability forStay(List<DailyInventory> inventories, DateRange dates) {
        Map<LocalDate, Integer> byDate = new HashMap<>();
        if (inventories != null) {
            for (DailyInventory inventory : inventories) {
                if (!dates.covers(inventory.date())) {
                    continue; // 요청 기간 밖의 날짜는 판정에 쓰지 않는다.
                }
                // 같은 날짜가 중복으로 오면 보수적으로 작은 값을 택한다.
                byDate.merge(inventory.date(), inventory.remainingRooms(), Math::min);
            }
        }

        int minimum = Integer.MAX_VALUE;
        for (LocalDate date : dates.stayDates()) {
            // 날짜가 빠져 있으면 재고 0 으로 본다. 알 수 없는 것을 있다고 가정하지 않는다.
            int remaining = byDate.getOrDefault(date, 0);
            minimum = Math.min(minimum, remaining);
        }
        return new Availability(minimum == Integer.MAX_VALUE ? 0 : minimum);
    }

    /** 0 이면 예약 불가다. */
    public boolean bookable() {
        return availableRooms > 0;
    }
}
