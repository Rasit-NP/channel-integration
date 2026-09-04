package com.channel.integration.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.channel.integration.port.FailureReason;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;

/**
 * 전송 계층 실패의 분류를 고정한다.
 *
 * <p>두 어댑터가 이 클래스를 공유하므로 여기서 한 번만 확인한다. 어댑터 테스트에 나눠 넣으면
 * 같은 규칙을 공급사 수만큼 되풀이하게 되고, 공급사가 늘 때 한쪽만 고치는 일이 생긴다.
 *
 * <p><b>이 테스트가 생긴 계기는 연결 타임아웃이 {@code SUPPLIER_ERROR} 로 분류되고 있던 것이다.</b>
 * 그때까지 타임아웃을 확인하는 테스트는 전부 <b>응답</b> 타임아웃이었고, 연결 쪽은 아무도 보고
 * 있지 않았다. 구간이 셋이면 셋을 다 확인해야 한다.
 */
class HttpFailuresTest {

    private static WebClientRequestException requestFailure(Throwable cause) {
        return new WebClientRequestException(
                cause, HttpMethod.GET, URI.create("http://supplier.test/x"), new HttpHeaders());
    }

    @Nested
    @DisplayName("전송 계층 타임아웃 — 구간이 셋이고 예외 타입이 다 다르다")
    class Timeouts {

        @Test
        @DisplayName("응답 제한을 넘기면 TIMEOUT")
        void responseTimeout() {
            assertThat(HttpFailures.fromThrowable(new TimeoutException("Did not observe any item")))
                    .isEqualTo(FailureReason.TIMEOUT);
        }

        @Test
        @DisplayName("읽기 제한을 넘기면 TIMEOUT")
        void readTimeout() {
            assertThat(HttpFailures.fromThrowable(ReadTimeoutException.INSTANCE))
                    .isEqualTo(FailureReason.TIMEOUT);
        }

        @Test
        @DisplayName("연결 제한을 넘기면 TIMEOUT — 감싸여 온다")
        void connectTimeoutWrapped() {
            // 실제로 WebClient 는 연결 실패를 WebClientRequestException 으로 감싸 던진다.
            // 감싼 것만 보고 원인을 안 보면 이 경우가 통째로 빠진다.
            ConnectTimeoutException connectTimeout =
                    new ConnectTimeoutException("connection timed out after 2000 ms: /10.0.0.1:80");

            assertThat(HttpFailures.fromThrowable(requestFailure(connectTimeout)))
                    .isEqualTo(FailureReason.TIMEOUT);
        }

        @Test
        @DisplayName("연결 제한 예외가 그대로 와도 TIMEOUT")
        void connectTimeoutBare() {
            assertThat(HttpFailures.fromThrowable(new ConnectTimeoutException("connection timed out")))
                    .isEqualTo(FailureReason.TIMEOUT);
        }

        /**
         * 이 테스트가 <b>왜 연결 타임아웃을 따로 적어야 하는지</b>를 말한다. 이름이 비슷해서 묶여
         * 있겠거니 하면 빠지는데, 실제로 그렇게 빠져 있었다.
         */
        @Test
        @DisplayName("연결 제한 예외는 다른 두 타임아웃 타입과 상속 관계가 없다")
        void connectTimeoutIsUnrelatedToTheOtherTimeoutTypes() {
            Object connectTimeout = new ConnectTimeoutException("connection timed out");

            assertThat(connectTimeout).isNotInstanceOf(TimeoutException.class);
            assertThat(connectTimeout).isNotInstanceOf(ReadTimeoutException.class);
            // 대신 이쪽을 상속한다. 그래서 연결 거절과 한 갈래로 묶여 보인다.
            assertThat(connectTimeout).isInstanceOf(ConnectException.class);
        }
    }

    @Nested
    @DisplayName("타임아웃이 아닌 전송 실패 — 기다리지 않고 실패한 것들")
    class NotTimeouts {

        /**
         * <b>연결 거절은 타임아웃이 아니다.</b> 상속상 연결 타임아웃과 같은 갈래로 보이지만,
         * 기다리다 끊긴 것과 즉시 거절된 것은 다른 사건이다. 이 구분이 깨지면 위 수정이
         * {@code ConnectException} 전체를 타임아웃으로 만들어 버린 것이 된다.
         */
        @Test
        @DisplayName("연결 거절은 SUPPLIER_ERROR — 연결 타임아웃과 갈린다")
        void connectionRefused() {
            assertThat(HttpFailures.fromThrowable(requestFailure(new ConnectException("Connection refused"))))
                    .isEqualTo(FailureReason.SUPPLIER_ERROR);
        }

        @Test
        @DisplayName("본문을 해석할 수 없으면 MALFORMED_RESPONSE")
        void undecodableBody() {
            assertThat(HttpFailures.fromThrowable(new DecodingException("JSON decoding error")))
                    .isEqualTo(FailureReason.MALFORMED_RESPONSE);
        }

        @Test
        @DisplayName("어디에도 넣기 어려우면 UNKNOWN")
        void unclassified() {
            assertThat(HttpFailures.fromThrowable(new IllegalStateException("?")))
                    .isEqualTo(FailureReason.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("상태 코드로 알리는 실패")
    class StatusCodes {

        @Test
        @DisplayName("상태 코드가 사유로 옮겨진다")
        void mapsStatusCodes() {
            assertThat(HttpFailures.fromStatusCode(400)).isEqualTo(FailureReason.INVALID_REQUEST);
            assertThat(HttpFailures.fromStatusCode(401)).isEqualTo(FailureReason.UNAUTHORIZED);
            assertThat(HttpFailures.fromStatusCode(403)).isEqualTo(FailureReason.UNAUTHORIZED);
            assertThat(HttpFailures.fromStatusCode(429)).isEqualTo(FailureReason.RATE_LIMITED);
            assertThat(HttpFailures.fromStatusCode(500)).isEqualTo(FailureReason.SUPPLIER_ERROR);
            assertThat(HttpFailures.fromStatusCode(503)).isEqualTo(FailureReason.SUPPLIER_ERROR);
            // 5xx 가 아닌 낯선 코드는 아는 사유로 밀어 넣지 않는다.
            assertThat(HttpFailures.fromStatusCode(418)).isEqualTo(FailureReason.UNKNOWN);
        }

        @Test
        @DisplayName("응답 예외는 상태 코드 판정으로 간다")
        void responseExceptionUsesStatusCode() {
            assertThat(HttpFailures.fromThrowable(
                    new WebClientResponseException(503, "Service Unavailable", null, null, null)))
                    .isEqualTo(FailureReason.SUPPLIER_ERROR);
        }
    }

    @Nested
    @DisplayName("설명 문구")
    class Describe {

        @Test
        @DisplayName("상태 코드만 남기고 공급사 응답 본문은 흘리지 않는다")
        void keepsSupplierBodyOut() {
            WebClientResponseException error = new WebClientResponseException(
                    503, "Service Unavailable", null,
                    "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"내부 사정\"}".getBytes(), null);

            assertThat(HttpFailures.describe(error))
                    .isEqualTo("HTTP 503")
                    .doesNotContain("SERVICE_UNAVAILABLE");
        }

        @Test
        @DisplayName("그 밖의 예외는 타입 이름만 남긴다")
        void keepsMessageOut() {
            assertThat(HttpFailures.describe(new ConnectTimeoutException("connection timed out to 10.0.0.1")))
                    .isEqualTo("ConnectTimeoutException")
                    .doesNotContain("10.0.0.1");
        }
    }
}
