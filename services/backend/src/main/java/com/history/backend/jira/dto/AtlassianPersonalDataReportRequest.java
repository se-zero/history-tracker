package com.history.backend.jira.dto;

import java.util.List;

// POST report-accounts 요청 본문 — updatedAt은 호출부에서 ISO_INSTANT로 미리 문자열화해 전달한다
public record AtlassianPersonalDataReportRequest(
        List<Account> accounts
) {

    public record Account(String accountId, String updatedAt) {
    }
}
