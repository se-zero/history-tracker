package com.history.backend.integration.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraPersonalDataReportScheduler: 스케줄러 위임 검증")
class JiraPersonalDataReportSchedulerTest {

    @Mock
    private JiraPersonalDataReportService jiraPersonalDataReportService;

    @Test
    @DisplayName("스케줄러가 JiraPersonalDataReportService.runReportCycle 위임 호출")
    void reportPersonalDataDelegatesToService() {
        when(jiraPersonalDataReportService.runReportCycle())
                .thenReturn(new JiraPersonalDataReportService.RunSummary(10, 8, 1, 1, 0));

        new JiraPersonalDataReportScheduler(jiraPersonalDataReportService).reportPersonalData();

        verify(jiraPersonalDataReportService).runReportCycle();
    }

    @Test
    @DisplayName("서비스가 예외를 던지면 그대로 재던진다")
    void reportPersonalDataRethrowsServiceException() {
        when(jiraPersonalDataReportService.runReportCycle()).thenThrow(new RuntimeException("boom"));

        JiraPersonalDataReportScheduler scheduler = new JiraPersonalDataReportScheduler(jiraPersonalDataReportService);

        assertThrows(RuntimeException.class, scheduler::reportPersonalData);
    }
}
