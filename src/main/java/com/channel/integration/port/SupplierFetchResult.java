package com.channel.integration.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 공급사 조회 결과. 성공과 실패를 <b>값으로</b> 표현한다.
 *
 * <p>예외를 던지지 않는 이유는 부분 실패를 허용해야 하기 때문이다. "A 는 성공, B 는 실패"를 한
 * 자료구조에 담아 다음 단계로 넘길 수 있어야 하는데, 예외로 다루면 호출부마다 try-catch 로
 * 감싸 다시 값으로 되돌리는 코드가 생긴다.
 *
 * <p>어댑터는 어떤 경우에도 이 타입을 돌려준다. 예외를 밖으로 흘리지 않는다.
 */
public sealed interface SupplierFetchResult<T> {

    record Success<T>(T value) implements SupplierFetchResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(FailureReason reason, String detail) implements SupplierFetchResult<T> {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }

    static <T> SupplierFetchResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> SupplierFetchResult<T> failure(FailureReason reason, String detail) {
        return new Failure<>(reason, detail);
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    /** 성공이면 값을, 실패면 빈 값을 준다. ({@code Success.value()} 접근자와 이름이 겹치지 않게 분리) */
    default Optional<T> asOptional() {
        return this instanceof Success<T>(T v) ? Optional.of(v) : Optional.empty();
    }

    default Optional<FailureReason> failureReason() {
        return this instanceof Failure<T>(FailureReason reason, String ignored)
                ? Optional.of(reason)
                : Optional.empty();
    }

    /**
     * 성공이면 값을, 실패면 빈 목록을 준다. 부분 실패를 허용하며 결과를 모을 때 쓴다.
     * 실패했다는 사실 자체는 별도로 집계해야 하므로, 이 메서드만으로 실패를 삼키지 않도록 주의한다.
     */
    static <E> List<E> valuesOrEmpty(SupplierFetchResult<List<E>> result) {
        return result.asOptional().orElseGet(List::of);
    }
}
