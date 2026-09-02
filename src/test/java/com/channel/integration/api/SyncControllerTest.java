package com.channel.integration.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.channel.integration.application.PropertySyncService;
import com.channel.integration.application.SyncReport;
import com.channel.integration.application.SyncReport.SupplierSync;
import com.channel.integration.domain.SupplierCode;
import com.channel.integration.port.FailureReason;

/**
 * 동기화 응답의 형태를 고정한다.
 *
 * <p>일부 공급사가 실패해도 <b>200</b> 이라는 점이 핵심이다. 나머지는 반영됐으므로 요청이
 * 실패한 것이 아니다. 대신 무엇이 실패했는지가 본문에 드러나야 한다.
 */
@WebMvcTest(SyncController.class)
class SyncControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PropertySyncService service;

    @Test
    @DisplayName("부분 실패도 200 이고, 실패한 공급사와 사유가 본문에 남는다")
    void reportsPartialFailureWithTwoHundred() throws Exception {
        given(service.synchronize()).willReturn(new SyncReport(List.of(
                SupplierSync.synced(SupplierCode.of("A"), 2, 3),
                SupplierSync.failed(SupplierCode.of("B"), FailureReason.TIMEOUT, "ReadTimeoutException"))));

        mvc.perform(post("/internal/suppliers/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.properties").value(2))
                .andExpect(jsonPath("$.roomTypes").value(3))
                .andExpect(jsonPath("$.suppliers[0].supplier").value("A"))
                .andExpect(jsonPath("$.suppliers[0].status").value("OK"))
                .andExpect(jsonPath("$.suppliers[1].supplier").value("B"))
                .andExpect(jsonPath("$.suppliers[1].status").value("FAILED"))
                .andExpect(jsonPath("$.suppliers[1].reason").value("TIMEOUT"));
    }

    @Test
    @DisplayName("전부 성공하면 부분 실패 표시가 없고 사유 필드도 붙지 않는다")
    void omitsReasonWhenEverythingSucceeded() throws Exception {
        given(service.synchronize()).willReturn(new SyncReport(List.of(
                SupplierSync.synced(SupplierCode.of("A"), 2, 3))));

        mvc.perform(post("/internal/suppliers/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.suppliers[0].reason").doesNotExist());
    }
}
