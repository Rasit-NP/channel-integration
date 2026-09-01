package com.channel.integration.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 하루치 잔여 객실 수. 객실 타입 단위이며, "그 타입 객실이 그날 n개 남았다"는 뜻이다. */
public record DailyInventory(LocalDate date, int remainingRooms) {

    public DailyInventory {
        Objects.requireNonNull(date, "date");
        if (remainingRooms < 0) {
            throw new IllegalArgumentException("잔여 객실 수는 음수일 수 없다: " + remainingRooms);
        }
    }
}
