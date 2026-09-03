package com.channel.integration.adapter.b;

import com.channel.integration.port.FailureReason;

/**
 * B 의 결과 코드를 실패 사유로 옮긴다.
 *
 * <p><b>이 클래스가 A 와 B 가 갈리는 자리다.</b> A 는 실패를 HTTP 상태 코드로 알리므로
 * {@code HttpFailures.fromStatusCode} 가 판정하지만, B 는 장애 상황에서도 200 을 주기 때문에
 * 상태 코드만 보면 <b>장애를 정상 응답으로 처리</b>하게 된다. 조용히 잘못된 결과를 내보내는
 * 종류의 버그라 가장 위험하다.
 *
 * <p>판정은 여기 갇혀 있고 바깥으로는 {@link FailureReason} 만 나간다. 도메인은 B 가 본문 코드로
 * 실패를 알린다는 사실을 알지 못한다.
 *
 * <p>연결 실패·타임아웃처럼 <b>어느 공급사에게나 똑같이 생기는 실패</b>는 여기서 다루지 않는다.
 * 그건 전송 계층의 일이라 A 와 같은 {@code HttpFailures} 를 쓴다. 층이 다르다.
 */
final class SupplierBResults {

    /** 성공을 뜻하는 코드. */
    private static final String SUCCESS = "0000";

    private SupplierBResults() {
    }

    static boolean succeeded(String resultCode) {
        return SUCCESS.equals(resultCode);
    }

    /**
     * 성공이 아닌 코드를 사유로 옮긴다.
     *
     * <p>대응은 A 의 상태 코드 판정과 <b>같은 해상도</b>다. 잘못된 요청·인증 실패·호출 한도 초과가
     * 각각 구분되므로, 통지 방식이 달라도 응답에 담기는 정보가 줄지 않는다. 그것이 "같은 실패로
     * 정규화한다"는 말의 실제 내용이다.
     *
     * <table border="1">
     *   <caption>결과 코드 대응</caption>
     *   <tr><td>{@code E400}</td><td>{@link FailureReason#INVALID_REQUEST}</td></tr>
     *   <tr><td>{@code E401}</td><td>{@link FailureReason#UNAUTHORIZED}</td></tr>
     *   <tr><td>{@code E429}</td><td>{@link FailureReason#RATE_LIMITED}</td></tr>
     *   <tr><td>{@code E500}, {@code E503}</td><td>{@link FailureReason#SUPPLIER_ERROR}</td></tr>
     * </table>
     *
     * <p>목록에 없는 코드는 {@link FailureReason#UNKNOWN} 이다. 공급사가 우리가 모르는 코드를
     * 보냈다는 뜻이라, 아는 사유로 밀어 넣는 것보다 모른다고 남기는 편이 낫다.
     */
    static FailureReason reasonOf(String resultCode) {
        if (resultCode == null || resultCode.isBlank()) {
            // 성공도 실패도 아니다. 봉투가 계약을 지키지 않은 것이므로 변환 실패로 본다.
            return FailureReason.MALFORMED_RESPONSE;
        }
        return switch (resultCode) {
            case "E400" -> FailureReason.INVALID_REQUEST;
            case "E401" -> FailureReason.UNAUTHORIZED;
            case "E429" -> FailureReason.RATE_LIMITED;
            case "E500", "E503" -> FailureReason.SUPPLIER_ERROR;
            default -> FailureReason.UNKNOWN;
        };
    }
}
