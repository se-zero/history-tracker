package com.history.backend.jira.dto;

import java.util.List;

// POST report-accounts 200 응답 — status는 "closed"/"updated" 외 값도 내려올 수 있다
public record AtlassianPersonalDataReportResponse(
        List<Account> accounts
) {

    public record Account(String accountId, String status) {
    }
}
