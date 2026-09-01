package com.channel.mock;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 외부 공급사를 흉내내는 Mock 서버.
 *
 * <p>본 애플리케이션(8080)과 반드시 다른 포트(9090)로 뜬다. 같은 포트에 두면 자기 자신을
 * HTTP 로 호출하게 되어, 스레드가 묶이면서 연동 문제로 오해하기 쉬운 실패가 생긴다.
 *
 * <p>설정을 {@code application.yaml} 대신 기본 프로퍼티로 두었다. 이 모듈은 본 애플리케이션의
 * 테스트 의존성으로도 쓰이는데, 두 모듈이 각자 클래스패스 루트에 {@code application.yaml} 을
 * 두면 어느 쪽이 읽힐지가 클래스패스 순서에 좌우된다. 기본 프로퍼티는 명령행 인자나 테스트의
 * 랜덤 포트 설정으로 언제든 덮어쓸 수 있다.
 */
@SpringBootApplication
public class MockSupplierApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MockSupplierApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.application.name", "mock-supplier",
                "server.port", "9090"));
        application.run(args);
    }
}
