package com.channel.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 외부 공급사를 흉내내는 Mock 서버.
 *
 * <p>본 애플리케이션(8080)과 반드시 다른 포트(9090)로 뜬다. 같은 포트에 두면 자기 자신을
 * HTTP 로 호출하게 되어, 스레드가 묶이면서 연동 문제로 오해하기 쉬운 실패가 생긴다.
 */
@SpringBootApplication
public class MockSupplierApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockSupplierApplication.class, args);
    }
}
