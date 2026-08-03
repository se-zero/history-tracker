package com.history.backend.integration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "atlassian.personal-data-report", name = "enabled", havingValue = "true")
public class JiraPersonalDataReportScheduler {

    private final JiraPersonalDataReportService jiraPersonalDataReportService;

    // 매일 실행하되, 실제 보고 대상 여부는 서비스의 due_before(7일 주기) 게이트가 판단한다
    @Scheduled(cron = "${atlassian.personal-data-report.cron}")
    public void reportPersonalData() {
        try {
            JiraPersonalDataReportService.RunSummary summary = jiraPersonalDataReportService.runReportCycle();
            log.info(
                    "Jira personal data report cycle done. reported={} ok={} erased={} refreshed={} skipped={}",
                    summary.reported(), summary.ok(), summary.erased(), summary.refreshed(), summary.skipped());
        } catch (RuntimeException exception) {
            // 실패 로그 기록 후 재던져 스케줄러 에러 처리에 위임
            log.error("Failed to run Jira personal data report cycle.", exception);
            throw exception;
        }
    }
}
