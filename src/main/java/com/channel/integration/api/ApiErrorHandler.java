package com.channel.integration.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 잘못된 요청을 400 으로 번역한다.
 *
 * <p>도메인 타입들이 생성 시점에 스스로를 검증하므로(체크아웃이 체크인보다 뒤인가, 성인이 1명
 * 이상인가), 컨트롤러에 같은 검증을 한 벌 더 두지 않는다. 대신 그 예외를 여기서 상태 코드로
 * 옮긴다. <b>규칙이 한 곳에만 있어야 두 곳이 어긋나지 않는다.</b>
 *
 * <p>공급사 조회가 실패한 경우는 여기로 오지 않는다. 그건 부분 실패라 200 으로 나가고 본문에
 * 담긴다. 여기서 다루는 것은 <b>요청 자체가 성립하지 않는</b> 경우뿐이다.
 */
@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** 도메인이 거부한 조건. 메시지가 사람이 읽을 수 있는 설명이라 그대로 내보낸다. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidCriteria(IllegalArgumentException e) {
        log.debug("잘못된 검색 조건: {}", e.getMessage());
        return badRequest(e.getMessage());
    }

    /** 날짜 형식이 깨졌거나 인원이 숫자가 아닌 경우. 내부 예외 문구는 흘리지 않는다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("파라미터 형식이 올바르지 않다: " + e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return badRequest("필수 파라미터가 없다: " + e.getParameterName());
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", message));
    }

    record ErrorResponse(String error, String message) {
    }
}
