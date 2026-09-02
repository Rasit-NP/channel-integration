package com.channel.integration.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 검색 조립 설정을 바인딩한다. */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {
}
