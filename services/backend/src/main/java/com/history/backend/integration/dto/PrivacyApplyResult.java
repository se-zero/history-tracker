package com.history.backend.integration.dto;

// ai-engine POST /privacy/jira/accounts/apply 응답 — 이번 적용에서 처리한 건수.
public record PrivacyApplyResult(int erased, int refreshed, int stamped) {
}
