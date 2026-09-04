package com.channel.integration.adapter.support;

import java.util.concurrent.TimeoutException;

import org.springframework.core.codec.CodecException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.channel.integration.port.FailureReason;

import io.netty.channel.ConnectTimeoutException;
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
            // 타임아웃 판정을 한 자리로 모은다. 구간마다 예외 타입이 다르고, 어떤 것은 그대로
            // 오고 어떤 것은 감싸여 오기 때문에 목록을 두 곳에 두면 한쪽에만 타입을 더하다
            // 분류가 갈린다 — 실제로 연결 타임아웃이 그렇게 빠져 있었다.
            case Throwable t when hasTimeoutCause(t) -> FailureReason.TIMEOUT;
            case CodecException ignored -> FailureReason.MALFORMED_RESPONSE;
            // 연결 자체가 안 된 경우. 공급사가 떠 있지 않거나 경로에 문제가 있다.
            // 시간이 넘어 끊긴 것은 위에서 이미 걸러졌으므로, 여기 남는 것은 거절처럼
            // 기다리지 않고 즉시 실패한 경우다.
            case WebClientRequestException ignored -> FailureReason.SUPPLIER_ERROR;
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

    /**
     * 전송 계층 타임아웃인가.
     *
     * <p><b>구간마다 예외 타입이 다르다.</b> 셋을 한 목록에 모아 두는 이유는, 나눠 두면 한쪽에만
     * 타입을 더하다 같은 사건이 다른 사유로 분류되기 때문이다.
     *
     * <table border="1">
     *   <caption>구간별 예외 타입</caption>
     *   <tr><td>응답 제한 (Reactor {@code timeout()})</td><td>{@link TimeoutException}</td></tr>
     *   <tr><td>읽기 제한 (Netty)</td><td>{@link ReadTimeoutException}</td></tr>
     *   <tr><td>연결 제한 (Netty)</td><td>{@link ConnectTimeoutException}</td></tr>
     * </table>
     *
     * <p>{@link ConnectTimeoutException} 을 따로 적어야 하는 이유는 그것이 {@code ConnectException}
     * 을 상속할 뿐 <b>다른 두 타입과 아무 관계가 없기</b> 때문이다. 이름만 보고 묶이겠거니 하면
     * 빠진다.
     *
     * <p>그렇다고 {@code ConnectException} 전체를 타임아웃으로 볼 수는 없다. <b>연결 거절은
     * 기다리지 않고 즉시 실패하는 다른 사건</b>이고, 그건 재시도 판단도 달라진다.
     */
    private static boolean isTimeout(Throwable error) {
        return error instanceof TimeoutException
                || error instanceof ReadTimeoutException
                || error instanceof ConnectTimeoutException;
    }

    /** 감싸여 온 타임아웃까지 본다. {@code WebClientRequestException} 이 원인을 안고 온다. */
    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (isTimeout(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
