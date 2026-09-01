package com.channel.integration.adapter.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/**
 * 공급사용 {@link WebClient} 를 만든다. 모든 어댑터가 같은 방식으로 타임아웃과 인증 헤더를
 * 걸도록 한 곳에 모았다.
 *
 * <p>연결 타임아웃은 전송 계층(Netty)에 건다. 응답 타임아웃은 어댑터가 호출 흐름에서
 * {@code timeout()} 으로 건다. 연결 수립 실패와 응답 지연은 원인이 다르므로 층을 나눴다.
 */
public final class SupplierWebClients {

    /** 두 공급사가 공통으로 쓰는 인증 헤더. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    private SupplierWebClients() {
    }

    /**
     * @param builder Spring Boot 가 구성해 둔 빌더. 컨텍스트의 Jackson 설정을 그대로 쓰기 위해
     *                {@code WebClient.builder()} 를 직접 부르지 않고 주입받은 것을 쓴다.
     */
    public static WebClient create(WebClient.Builder builder, SupplierHttpProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()))
                // 전송 계층에도 응답 제한을 걸어, 연결만 잡고 아무것도 오지 않는 상태를 막는다.
                .responseTimeout(properties.responseTimeout());

        return builder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
