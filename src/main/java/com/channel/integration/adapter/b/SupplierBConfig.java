package com.channel.integration.adapter.b;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.adapter.support.SupplierWebClients;
import com.channel.integration.port.SupplierAdapter;

/**
 * 공급사 B 어댑터를 등록한다.
 *
 * <p>A 의 설정 클래스와 모양이 같다. 공급사를 늘릴 때 늘어나는 것이 이 한 쌍(설정 + 구현체)뿐이라는
 * 것이 이 구조의 주장이고, B 가 그 주장을 실제로 밟은 첫 사례다.
 */
@Configuration
@EnableConfigurationProperties(SupplierBProperties.class)
class SupplierBConfig {

    @Bean
    SupplierAdapter supplierBAdapter(WebClient.Builder builder, SupplierBProperties properties) {
        return new SupplierBAdapter(SupplierWebClients.create(builder, properties), properties);
    }
}
