package com.channel.integration.adapter.a;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.channel.integration.adapter.support.SupplierWebClients;
import com.channel.integration.port.SupplierAdapter;

/**
 * 공급사 A 어댑터를 등록한다.
 *
 * <p>공급사를 추가할 때 이런 설정 클래스와 어댑터 구현체만 늘어난다. 검색 로직은 등록된
 * {@link SupplierAdapter} 목록을 주입받을 뿐이라 고칠 곳이 없다.
 */
@Configuration
@EnableConfigurationProperties(SupplierAProperties.class)
class SupplierAConfig {

    @Bean
    SupplierAdapter supplierAAdapter(WebClient.Builder builder, SupplierAProperties properties) {
        return new SupplierAAdapter(SupplierWebClients.create(builder, properties), properties);
    }
}
