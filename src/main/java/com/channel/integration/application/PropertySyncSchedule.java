package com.channel.integration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 동기화를 <b>언제</b> 돌릴지 정한다. 어떻게 도는지는 {@link PropertySyncService} 가 안다.
 *
 * <p>세 가지 계기를 둔다 — 기동 시 1회, 주기적 갱신, 그리고 수동 트리거(API). 검색할 때마다
 * 부르는 안은 버렸다. 자주 바뀌지 않는 정적 데이터를 매 요청마다 가져오면 응답이 느려지고
 * 호출 한도만 쓴다.
 *
 * <p><b>여기서 가장 중요한 것은 동기화 실패가 기동 실패로 번지지 않는 것이다.</b> 공급사가
 * 죽어 있어도 애플리케이션은 뜨고, 이미 저장된 매핑으로 검색을 계속 서빙한다. 매핑은 우리 DB 에
 * 있으므로 공급사가 응답하지 않아도 "무엇을 물어볼지"는 알고 있다.
 */
@Component
class PropertySyncSchedule {

    private static final Logger log = LoggerFactory.getLogger(PropertySyncSchedule.class);

    private final PropertySyncService service;
    private final boolean onStartup;

    PropertySyncSchedule(
            PropertySyncService service,
            @Value("${supplier.sync.on-startup:true}") boolean onStartup) {
        this.service = service;
        this.onStartup = onStartup;
    }

    /**
     * 기동 직후 1회. 웹 서버가 이미 뜬 뒤에 도는 시점이라, 여기서 오래 걸려도 기동 자체가
     * 막히지는 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (!onStartup) {
            log.info("기동 시 동기화가 꺼져 있다 (supplier.sync.on-startup=false)");
            return;
        }
        runQuietly("기동");
    }

    /**
     * 주기적 갱신. 첫 실행을 한 주기 뒤로 미루는 것은 기동 시 이미 한 번 돌았기 때문이다.
     */
    @Scheduled(
            fixedDelayString = "${supplier.sync.interval:1h}",
            initialDelayString = "${supplier.sync.interval:1h}")
    void periodically() {
        runQuietly("주기");
    }

    /**
     * 실패를 삼키는 것이 여기서는 의도다. 동기화는 검색을 돕는 사전 작업이지 기동 조건이 아니다.
     * 실패 사실은 {@link PropertySyncService} 가 공급사별로 이미 로그에 남긴다.
     */
    private void runQuietly(String trigger) {
        try {
            SyncReport report = service.synchronize();
            if (report.partial()) {
                log.warn("{} 동기화에서 일부 공급사가 실패했다. 그 공급사의 기존 매핑은 유지된다", trigger);
            }
        } catch (RuntimeException e) {
            // 저장소가 죽은 경우 등. 여기서 던지면 기동이 막히거나 스케줄러가 멈춘다.
            log.warn("{} 동기화가 예외로 끝났다: {}", trigger, e.toString());
        }
    }
}
