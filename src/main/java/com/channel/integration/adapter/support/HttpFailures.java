package com.channel.integration.adapter.support;

import java.util.concurrent.TimeoutException;

import org.springframework.core.codec.CodecException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.channel.integration.port.FailureReason;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.Exceptions;

/**
 * 전송 계층에서 생긴 문제를 {@link FailureReason} 으로 옮긴다.
 *
 * <p>여기서 다루는 것은 <b>어느 공급사에게나 똑같이 생기는 실패</b>다. 연결이 안 되거나, 응답이
 * 늦거나, 본문을 해석할 수 없는 경우다. 반면 "이 공급사가 실패를 어떻게 알리는가"는 공급사마다
 * 달라서 각 어댑터가 판단한다. 상태 코드로 알리는 곳도 있고, 항상 200 을 주면서 응답 본문의
 * 코드로만 알리는 곳도 있다.
 */
public final class HttpFailures {

    private HttpFailures() {
    }

    /** HTTP 상태 코드로 실패를 알리는 공급사용 변환. */
    public static FailureReason fromStatusCode(int statusCode) {
        return switch (statusCode) {
            case 400 -> FailureReason.INVALID_REQUEST;
            case 401, 403 -> FailureReason.UNAUTHORIZED;
            case 429 -> FailureReason.RATE_LIMITED;
            default -> statusCode >= 500 ? FailureReason.SUPPLIER_ERROR : FailureReason.UNKNOWN;
        };
    }

    /** 호출 도중 발생한 예외를 사유로 옮긴다. */
    public static FailureReason fromThrowable(Throwable error) {
        Throwable cause = unwrap(error);
        return switch (cause) {
            case WebClientResponseException e -> fromStatusCode(e.getStatusCode().value());
            case TimeoutException ignored -> FailureReason.TIMEOUT;
            case ReadTimeoutException ignored -> FailureReason.TIMEOUT;
            case CodecException ignored -> FailureReason.MALFORMED_RESPONSE;
            // 연결 자체가 안 된 경우. 공급사가 떠 있지 않거나 경로에 문제가 있다.
            case WebClientRequestException e -> hasTimeoutCause(e)
                    ? FailureReason.TIMEOUT
                    : FailureReason.SUPPLIER_ERROR;
            default -> FailureReason.UNKNOWN;
        };
    }

    /** 로그와 응답에 남길 짧은 설명. 공급사 원문을 그대로 흘리지 않는다. */
    public static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof WebClientResponseException e) {
            return "HTTP " + e.getStatusCode().value();
        }
        return cause.getClass().getSimpleName();
    }

    /** Reactor 가 감싼 예외를 원래 것으로 되돌린다. */
    private static Throwable unwrap(Throwable error) {
        return Exceptions.unwrap(error);
    }

    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof ReadTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
