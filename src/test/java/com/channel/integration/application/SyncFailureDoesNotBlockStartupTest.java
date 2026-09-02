package com.channel.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import com.channel.integration.port.MappingRepository;

/**
 * 공급사가 응답하지 않는 상태로 기동한다.
 *
 * <p>설계에 "기동 시 동기화가 실패해도 애플리케이션은 뜨고, 이미 저장된 매핑으로 검색을 계속
 * 서빙한다"고 적어두었다. <b>문서에만 적힌 약속은 지켜지지 않으므로</b> 실제로 닿지 않는 주소를
 * 물려 기동시켜 확인한다.
 *
 * <p>공급사 주소를 닫힌 포트로 돌려 연결이 즉시 거절되게 하고, DB 는 메모리로 돌려 개발용
 * 데이터 파일을 건드리지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "supplier.a.base-url=http://localhost:1",
        "spring.datasource.url=jdbc:h2:mem:startup-test;DB_CLOSE_DELAY=-1",
        // 주기적 갱신이 테스트 도중 끼어들지 않게 한다. 여기서 보는 것은 기동 시 1회다.
        "supplier.sync.interval=1h"
})
class SyncFailureDoesNotBlockStartupTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private MappingRepository repository;

    @Test
    @DisplayName("공급사에 닿지 못해도 애플리케이션은 뜬다")
    void startsEvenWhenSuppliersAreUnreachable() {
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBean(PropertySyncService.class)).isNotNull();
    }

    @Test
    @DisplayName("동기화가 실패했으므로 매핑은 비어 있다 — 기동은 그것과 무관하다")
    void leavesMappingsEmptyWithoutFailing() {
        // 매핑이 비었다는 것은 이 상황에서 정상이다. 검색은 "물어볼 숙소가 없다"로 답하면 된다.
        assertThat(repository.load().isEmpty()).isTrue();
    }
}
