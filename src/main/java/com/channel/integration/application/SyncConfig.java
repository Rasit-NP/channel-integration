package com.channel.integration.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기적 동기화를 켠다. {@link PropertySyncSchedule} 의 {@code @Scheduled} 가 동작하려면
 * 필요하다.
 */
@Configuration
@EnableScheduling
class SyncConfig {
}
