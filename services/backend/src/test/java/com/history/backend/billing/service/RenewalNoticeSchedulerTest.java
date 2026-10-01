package com.history.backend.billing.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalNoticeScheduler: 스케줄러 위임")
class RenewalNoticeSchedulerTest {

    @Mock
    private RenewalNoticeService renewalNoticeService;

    @Test
    @DisplayName("스케줄러가 sendDueNotices에 위임한다")
    void sendDueNoticesDelegatesToService() {
        when(renewalNoticeService.sendDueNotices(org.mockito.ArgumentMatchers.any())).thenReturn(2);

        new RenewalNoticeScheduler(renewalNoticeService).sendDueNotices();

        verify(renewalNoticeService).sendDueNotices(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("서비스가 예외를 던지면 그대로 재던진다")
    void sendDueNoticesRethrowsServiceException() {
        when(renewalNoticeService.sendDueNotices(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));
        RenewalNoticeScheduler scheduler = new RenewalNoticeScheduler(renewalNoticeService);

        assertThrows(RuntimeException.class, scheduler::sendDueNotices);
    }
}
