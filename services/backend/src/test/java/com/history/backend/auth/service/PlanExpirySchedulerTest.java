package com.history.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanExpiryScheduler: 스케줄러 위임 검증")
class PlanExpirySchedulerTest {

    @Mock
    private PlanExpiryService planExpiryService;

    @Test
    @DisplayName("스케줄러가 PlanExpiryService.downgradeExpiredPlans 위임 호출")
    void downgradeExpiredPlansDelegatesToService() {
        when(planExpiryService.downgradeExpiredPlans()).thenReturn(3);

        new PlanExpiryScheduler(planExpiryService).downgradeExpiredPlans();

        verify(planExpiryService).downgradeExpiredPlans();
    }

    @Test
    @DisplayName("서비스가 예외를 던지면 그대로 재던진다")
    void downgradeExpiredPlansRethrowsServiceException() {
        when(planExpiryService.downgradeExpiredPlans()).thenThrow(new RuntimeException("boom"));

        PlanExpiryScheduler scheduler = new PlanExpiryScheduler(planExpiryService);

        assertThrows(RuntimeException.class, scheduler::downgradeExpiredPlans);
    }
}
